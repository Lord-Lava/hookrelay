package com.hookrelay.delivery.application;

import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hookrelay.fanout")
public record FanoutProperties(
    boolean enabled,
    boolean rolloutReviewed,
    Instant acceptEventsFrom,
    int concurrency,
    int prefetch) {
  public FanoutProperties {
    if (concurrency < 1 || concurrency > 8 || prefetch < 1 || prefetch > 50)
      throw new IllegalArgumentException("Fan-out concurrency or prefetch outside bounds");
    if (enabled && (!rolloutReviewed || acceptEventsFrom == null))
      throw new IllegalArgumentException("Fan-out requires reviewed rollout and an event cutoff");
  }
}
