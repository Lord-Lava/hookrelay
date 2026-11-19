package com.hookrelay.delivery.domain;

import java.time.Duration;

/** Persisted cycle limits only; retry calculation belongs to a later phase. */
public record DeliveryPolicy(String version, int maxAttempts, Duration horizon) {
  public DeliveryPolicy {
    if (version == null
        || !version.matches("[A-Za-z0-9_-]{1,32}")
        || maxAttempts < 1
        || maxAttempts > 100
        || horizon == null
        || horizon.compareTo(Duration.ofSeconds(1)) < 0
        || horizon.compareTo(Duration.ofHours(24)) > 0
        || horizon.getNano() != 0) {
      throw new IllegalArgumentException("Invalid delivery policy");
    }
  }
}
