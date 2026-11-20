package com.hookrelay.delivery.messaging;

import com.hookrelay.delivery.application.EventFanoutService;
import com.hookrelay.delivery.application.FanoutProperties;
import com.hookrelay.delivery.application.RejectedNotification;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hookrelay.fanout", name = "enabled", havingValue = "true")
public final class FanoutConsumer implements ChannelAwareMessageListener, SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(FanoutConsumer.class);
  private final EventNotificationParser parser;
  private final EventFanoutService service;
  private final MeterRegistry metrics;
  private final SimpleMessageListenerContainer container;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean paused = new AtomicBoolean();
  private final AtomicInteger failures = new AtomicInteger();
  private final ScheduledExecutorService recovery =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("fanout-recovery").daemon(true).factory());

  public FanoutConsumer(
      ConnectionFactory connectionFactory,
      EventNotificationParser parser,
      EventFanoutService service,
      FanoutProperties properties,
      MeterRegistry metrics,
      @Value("${hookrelay.rabbitmq.queue}") String queue) {
    this.parser = parser;
    this.service = service;
    this.metrics = metrics;
    container = new SimpleMessageListenerContainer(connectionFactory);
    container.setQueueNames(queue);
    container.setConcurrentConsumers(properties.concurrency());
    container.setMaxConcurrentConsumers(properties.concurrency());
    container.setPrefetchCount(properties.prefetch());
    container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    container.setDefaultRequeueRejected(false);
    container.setForceStop(true);
    container.setForceCloseChannel(true);
    container.setShutdownTimeout(2000);
    container.setReceiveTimeout(250);
    container.setRecoveryInterval(5000);
    container.setAutoStartup(false);
    container.setMessageListener(this);
    container.setErrorHandler(error -> {
      log.error("Fan-out container rejected an unhandled message failure");
      throw new AmqpRejectAndDontRequeueException("FANOUT_CONTAINER_FAILURE", true, null);
    });
    container.afterPropertiesSet();
  }

  @Override
  public void onMessage(Message message, Channel channel) {
    // Leave buffered messages unacked while the supervisor closes the consumers.
    if (paused.get() || !running.get()) return;
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      EventFanoutService.Result result;
      try {
        result = service.process(parser.parse(message));
      } catch (RejectedNotification rejected) {
        reject(channel, tag, rejected.reason().name());
        return;
      } catch (RuntimeException failure) {
        if (retryable(failure)) {
          count("transient_failure");
          pause();
        } else {
          log.error(
              "Fan-out persistence invariant failed ({})", failure.getClass().getSimpleName());
          reject(channel, tag, "INVARIANT_FAILURE");
        }
        return;
      }
      // process() owns its transaction; commit has succeeded before this network call.
      channel.basicAck(tag, false);
      count(result == EventFanoutService.Result.CREATED ? "created" : "duplicate");
      failures.set(0);
    } catch (IOException acknowledgementFailure) {
      count("acknowledgement_failure");
      pause();
    }
  }

  static boolean retryable(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException
          || cause instanceof RecoverableDataAccessException
          || cause instanceof DataAccessResourceFailureException
          || cause instanceof org.springframework.transaction.CannotCreateTransactionException
          || cause instanceof org.springframework.transaction.TransactionSystemException
          || cause instanceof org.springframework.transaction.TransactionTimedOutException)
        return true;
      // Spring's default translator can leave PostgreSQL lock timeouts uncategorized.
      if (cause instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if (state != null
            && ((state.startsWith("08") || state.startsWith("53"))
                || java.util.Set.of("40001", "40P01", "55P03", "57014", "57P01", "57P02", "57P03")
                    .contains(state))) return true;
      }
    }
    return false;
  }

  private void reject(Channel channel, long tag, String reason) throws IOException {
    log.warn("Fan-out notification rejected ({})", reason);
    count("rejected");
    channel.basicReject(tag, false);
  }

  private void count(String outcome) {
    metrics.counter("hookrelay.fanout.messages", "outcome", outcome).increment();
  }

  private void pause() {
    if (running.get() && paused.compareAndSet(false, true)) {
      // Exactly one queued stop/restart per outage. Never stop/join on a listener thread.
      try {
        recovery.execute(() -> {
          synchronized (this) {
            container.stop();
          }
          if (running.get()) {
            int exponent = Math.min(failures.getAndIncrement(), 6);
            long base = Math.min(60000, 1000L << exponent);
            long delay = Math.min(60000, base + ThreadLocalRandom.current().nextLong(base / 5 + 1));
            log.warn("Fan-out paused; retrying consumer in {} ms", delay);
            recovery.schedule(
                () -> {
                  synchronized (this) {
                    if (running.get()) {
                      paused.set(false);
                      try {
                        container.start();
                      } catch (RuntimeException startFailure) {
                        pause();
                      }
                    }
                  }
                },
                delay,
                TimeUnit.MILLISECONDS);
          }
        });
      } catch (java.util.concurrent.RejectedExecutionException shutdown) {
        // Destruction can race the listener's last failure; channel closure redelivers it.
        if (running.get()) throw shutdown;
      }
    }
  }

  @Override
  public synchronized void start() {
    if (running.compareAndSet(false, true)) {
      paused.set(false);
      try {
        container.start();
      } catch (RuntimeException startFailure) {
        pause();
      }
    }
  }

  @Override
  public synchronized void stop() {
    running.set(false);
    container.stop();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  @PreDestroy
  public synchronized void destroy() {
    running.set(false);
    recovery.shutdownNow();
    container.destroy();
  }
}
