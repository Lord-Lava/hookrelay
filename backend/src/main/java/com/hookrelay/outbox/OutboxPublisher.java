package com.hookrelay.outbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "hookrelay.outbox",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxStore outboxStore;
  private final RabbitTemplate rabbitTemplate;
  private final String exchange;
  private final String routingKey;
  private final String lockOwner = UUID.randomUUID().toString();
  private final int batchSize;
  private final int leaseSeconds;
  private final int confirmTimeoutSeconds;
  private final int maxBackoffSeconds;
  private final boolean schedulingEnabled;

  public OutboxPublisher(
      OutboxStore outboxStore,
      RabbitTemplate rabbitTemplate,
      @Value("${hookrelay.rabbitmq.exchange}") String exchange,
      @Value("${hookrelay.rabbitmq.routing-key}") String routingKey,
      @Value("${hookrelay.outbox.batch-size:25}") int batchSize,
      @Value("${hookrelay.outbox.lease-seconds:30}") int leaseSeconds,
      @Value("${hookrelay.outbox.confirm-timeout-seconds:5}") int confirmTimeoutSeconds,
      @Value("${hookrelay.outbox.max-publish-backoff-seconds:60}") int maxBackoffSeconds,
      @Value("${hookrelay.outbox.scheduling-enabled:true}") boolean schedulingEnabled) {
    this.outboxStore = outboxStore;
    this.rabbitTemplate = rabbitTemplate;
    this.exchange = exchange;
    this.routingKey = routingKey;
    this.batchSize = batchSize;
    this.leaseSeconds = leaseSeconds;
    this.confirmTimeoutSeconds = confirmTimeoutSeconds;
    this.maxBackoffSeconds = maxBackoffSeconds;
    this.schedulingEnabled = schedulingEnabled;
  }

  /**
   * Scheduled entry point. Gated by {@code hookrelay.outbox.scheduling-enabled} so integration
   * tests can disable automatic execution while invoking {@link #publishPendingMessages()}
   * deterministically.
   */
  @Scheduled(fixedDelayString = "${hookrelay.outbox.poll-interval:1000}")
  public void scheduledPublish() {
    if (!schedulingEnabled) {
      return;
    }
    publishPendingMessages();
  }

  public void publishPendingMessages() {
    for (int index = 0; index < batchSize; index++) {
      var claimed = outboxStore.claimNext(lockOwner, leaseSeconds);
      if (claimed.isEmpty()) {
        return;
      }
      publish(claimed.get());
    }
  }

  void publish(OutboxMessage outboxMessage) {
    try {
      MessageProperties properties = new MessageProperties();
      properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      properties.setMessageId(outboxMessage.id().toString());
      Message message =
          new Message(outboxMessage.payloadJson().getBytes(StandardCharsets.UTF_8), properties);

      CorrelationData correlationData = new CorrelationData(outboxMessage.id().toString());
      rabbitTemplate.send(exchange, routingKey, message, correlationData);
      var confirm = correlationData.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
      if (!confirm.ack()) {
        throw new IllegalStateException("negative_publisher_confirm");
      }
      if (correlationData.getReturned() != null) {
        throw new IllegalStateException("unroutable_publisher_return");
      }
      outboxStore.markPublished(outboxMessage.id(), lockOwner);
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      long delay = retryDelaySeconds(outboxMessage.publishAttempts());
      String errorCode = "publisher_" + exception.getClass().getSimpleName();
      try {
        outboxStore.releaseForRetry(outboxMessage.id(), lockOwner, delay, errorCode);
      } catch (Exception releaseException) {
        log.error(
            "Failed to release outbox message {}; its lease will expire",
            outboxMessage.id(),
            releaseException);
      }
      log.warn(
          "Outbox publish failed for message {}; retrying in {} seconds ({})",
          outboxMessage.id(),
          delay,
          errorCode);
    }
  }

  private long retryDelaySeconds(int attempts) {
    int exponent = Math.clamp(attempts - 1, 0, 10);
    long base = Math.min(maxBackoffSeconds, 1L << exponent);
    long jitter = Math.max(0, Math.round(base * 0.2));
    return Math.min(
        maxBackoffSeconds,
        base + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1)));
  }
}
