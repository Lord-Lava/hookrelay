package com.hookrelay.delivery.application;

import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.delivery.repository.FanoutStore;
import com.hookrelay.delivery.signing.WebhookSigner;
import com.hookrelay.delivery.transport.WebhookTransport;
import com.hookrelay.endpoint.application.EndpointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Configuration for the delivery dispatcher and worker.
 *
 * <p>Enables scheduling for the dispatcher poll and recovery loops. All components are wired with
 * constructor injection.
 */
@Configuration
@EnableConfigurationProperties(DeliveryDispatcherProperties.class)
@EnableTransactionManagement
@EnableScheduling
@ConditionalOnProperty(name = "hookrelay.delivery.dispatcher.enabled", havingValue = "true")
public class DeliveryDispatcherConfiguration {

  @Bean
  public DeliveryDispatcher deliveryDispatcher(
      DeliveryStore deliveryStore,
      DeliveryWorker deliveryWorker,
      DispatchControlService dispatchControl,
      DeliveryDispatcherProperties properties) {
    return new DeliveryDispatcher(deliveryStore, deliveryWorker, dispatchControl, properties);
  }

  @Bean
  public DeliveryWorker deliveryWorker(
      DeliveryStore deliveryStore,
      FanoutStore fanoutStore,
      WebhookTransport transport,
      WebhookSigner signer,
      EndpointService endpointService,
      RetryScheduler retryScheduler,
      PlatformTransactionManager transactionManager) {
    return new DeliveryWorker(
        deliveryStore,
        fanoutStore,
        transport,
        signer,
        endpointService,
        retryScheduler,
        transactionManager);
  }

  @Bean
  public DispatchControlService dispatchControlService() {
    return new DispatchControlService();
  }

  @Bean
  public RetryScheduler retryScheduler(DeliveryDispatcherProperties properties) {
    return new RetryScheduler(
        properties.retryBaseSeconds(), properties.retryMaxSeconds(), properties.cycleDeadline());
  }
}
