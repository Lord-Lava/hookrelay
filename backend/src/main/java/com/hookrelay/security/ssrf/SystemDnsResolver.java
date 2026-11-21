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
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          Thread.ofPlatform().daemon().name("endpoint-dns-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    Future<InetAddress[]> future;
    try {
      future = executor.submit(() -> InetAddress.getAllByName(host));
    } catch (RejectedExecutionException exception) {
      throw new UnknownHostException("DNS capacity unavailable");
    }
    try {
      return future.get(2, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new UnknownHostException("DNS lookup interrupted");
    } catch (ExecutionException | TimeoutException exception) {
      throw new UnknownHostException("DNS lookup unavailable");
    } finally {
      future.cancel(true);
      executor.purge();
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
