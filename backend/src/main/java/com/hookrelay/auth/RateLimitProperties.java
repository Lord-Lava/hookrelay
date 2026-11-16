package com.hookrelay.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hookrelay.rate-limit")
public record RateLimitProperties(int requests, int windowSeconds) {
  public RateLimitProperties {
    if (requests < 1 || requests > 1000000 || windowSeconds < 1 || windowSeconds > 3600)
      throw new IllegalArgumentException("Invalid project rate limit configuration");
  }
}
