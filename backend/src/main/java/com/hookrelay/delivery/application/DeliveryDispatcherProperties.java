package com.hookrelay.delivery.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hookrelay.delivery.dispatcher")
public record DeliveryDispatcherProperties(
    Boolean enabled,
    Duration pollingInterval,
    int maxConcurrentClaims,
    Duration leaseDuration,
    Duration recoveryInterval,
    int maxConcurrentPerProject,
    int maxConcurrentPerEndpoint,
    int ratePerProjectPerSecond,
    int ratePerEndpointPerSecond,
    int retryBaseSeconds,
    int retryMaxSeconds,
    Duration cycleDeadline) {

  public DeliveryDispatcherProperties {
    enabled = enabled == null ? false : enabled;
    pollingInterval = pollingInterval == null ? Duration.ofMillis(250) : pollingInterval;
    maxConcurrentClaims = maxConcurrentClaims <= 0 ? 16 : maxConcurrentClaims;
    leaseDuration = leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration;
    recoveryInterval = recoveryInterval == null ? Duration.ofSeconds(5) : recoveryInterval;
    maxConcurrentPerProject = maxConcurrentPerProject <= 0 ? 4 : maxConcurrentPerProject;
    maxConcurrentPerEndpoint = maxConcurrentPerEndpoint <= 0 ? 1 : maxConcurrentPerEndpoint;
    ratePerProjectPerSecond = ratePerProjectPerSecond <= 0 ? 20 : ratePerProjectPerSecond;
    ratePerEndpointPerSecond = ratePerEndpointPerSecond <= 0 ? 5 : ratePerEndpointPerSecond;
    retryBaseSeconds = retryBaseSeconds <= 0 ? 10 : retryBaseSeconds;
    retryMaxSeconds = retryMaxSeconds <= 0 ? 3600 : retryMaxSeconds;
    cycleDeadline = cycleDeadline == null ? Duration.ofHours(24) : cycleDeadline;

    if (pollingInterval.isNegative()
        || pollingInterval.isZero()
        || leaseDuration.isNegative()
        || leaseDuration.isZero()
        || recoveryInterval.isNegative()
        || recoveryInterval.isZero()) {
      throw new IllegalArgumentException("Invalid dispatcher timing configuration");
    }
    if (maxConcurrentClaims < 1
        || maxConcurrentClaims > 1000
        || maxConcurrentPerProject < 1
        || maxConcurrentPerProject > 100
        || maxConcurrentPerEndpoint < 1
        || maxConcurrentPerEndpoint > 100) {
      throw new IllegalArgumentException("Invalid concurrency limits");
    }
    if (ratePerProjectPerSecond < 1
        || ratePerProjectPerSecond > 100000
        || ratePerEndpointPerSecond < 1
        || ratePerEndpointPerSecond > 100000) {
      throw new IllegalArgumentException("Invalid rate limits");
    }
    if (retryBaseSeconds < 1
        || retryBaseSeconds > 3600
        || retryMaxSeconds < retryBaseSeconds
        || retryMaxSeconds > 86400) {
      throw new IllegalArgumentException("Invalid retry configuration");
    }
    if (cycleDeadline.isNegative() || cycleDeadline.isZero()) {
      throw new IllegalArgumentException("Cycle deadline must be positive");
    }
  }
}
