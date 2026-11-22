package com.hookrelay.security.ssrf;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** Bounds callers and queued DNS work even when the platform resolver cannot be interrupted. */
@Component
public class SystemDnsResolver implements DnsResolver {
  private final DnsResolver lookup;
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          Thread.ofPlatform().daemon().name("endpoint-dns-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public SystemDnsResolver() {
    this(InetAddress::getAllByName);
  }

  SystemDnsResolver(DnsResolver lookup) {
    this.lookup = lookup;
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    Future<InetAddress[]> future;
    try {
      future = executor.submit(() -> lookup.resolve(host));
    } catch (RejectedExecutionException exception) {
      throw lookupFailure("DNS capacity unavailable", exception);
    }
    try {
      return future.get(2, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw lookupFailure("DNS lookup interrupted", exception);
    } catch (TimeoutException exception) {
      throw lookupFailure("DNS lookup timed out", exception);
    } catch (ExecutionException exception) {
      throw lookupFailure("DNS lookup unavailable", exception.getCause());
    } finally {
      future.cancel(true);
      executor.purge();
    }
  }

  private static UnknownHostException lookupFailure(String message, Throwable cause) {
    var failure = new UnknownHostException(message);
    failure.initCause(cause);
    return failure;
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
