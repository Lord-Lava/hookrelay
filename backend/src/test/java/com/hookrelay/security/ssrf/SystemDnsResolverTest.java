package com.hookrelay.security.ssrf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class SystemDnsResolverTest {
  @Test
  void givenLookupDoesNotComplete_whenResolve_thenTimeoutCauseIsPreserved() {
    var blockedLookup = new CountDownLatch(1);
    var resolver =
        new SystemDnsResolver(host -> {
          try {
            blockedLookup.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return new InetAddress[0];
        });
    try {
      assertThatThrownBy(() -> resolver.resolve("customer.example"))
          .isInstanceOf(UnknownHostException.class)
          .hasCauseInstanceOf(TimeoutException.class);
    } finally {
      blockedLookup.countDown();
      resolver.close();
    }
  }

  @Test
  void givenUnknownHost_whenResolve_thenOriginalFailureIsPreserved() {
    var failure = new UnknownHostException("customer.example");
    var resolver =
        new SystemDnsResolver(host -> {
          throw failure;
        });
    try {
      assertThatThrownBy(() -> resolver.resolve("customer.example"))
          .isInstanceOf(UnknownHostException.class)
          .hasCause(failure);
    } finally {
      resolver.close();
    }
  }
}
