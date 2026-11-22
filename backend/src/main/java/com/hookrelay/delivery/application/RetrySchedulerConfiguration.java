package com.hookrelay.delivery.application;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrySchedulerConfiguration {

  @Bean
  public RetryScheduler retryScheduler() {
    return new RetryScheduler(10, 3600, Duration.ofHours(24));
  }
}
