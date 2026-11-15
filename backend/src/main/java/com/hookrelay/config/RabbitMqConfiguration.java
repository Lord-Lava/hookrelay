package com.hookrelay.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitMqConfiguration {
  @Bean
  TopicExchange eventsExchange(@Value("${hookrelay.rabbitmq.exchange}") String exchange) {
    return new TopicExchange(exchange, true, false);
  }

  @Bean
  DirectExchange eventsDeadLetterExchange(
      @Value("${hookrelay.rabbitmq.dead-letter-exchange}") String exchange) {
    return new DirectExchange(exchange, true, false);
  }

  @Bean
  Queue eventIngestionQueue(
      @Value("${hookrelay.rabbitmq.queue}") String queue,
      @Value("${hookrelay.rabbitmq.dead-letter-exchange}") String dlx,
      @Value("${hookrelay.rabbitmq.dead-letter-routing-key}") String dlqRoutingKey) {
    return QueueBuilder.durable(queue)
        .withArgument("x-dead-letter-exchange", dlx)
        .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
        .build();
  }

  @Bean
  Queue eventIngestionDeadLetterQueue(
      @Value("${hookrelay.rabbitmq.dead-letter-queue}") String queue) {
    return QueueBuilder.durable(queue).build();
  }

  @Bean
  Binding eventIngestionBinding(
      Queue eventIngestionQueue,
      TopicExchange eventsExchange,
      @Value("${hookrelay.rabbitmq.routing-key}") String routingKey) {
    return BindingBuilder.bind(eventIngestionQueue).to(eventsExchange).with(routingKey);
  }

  @Bean
  Binding eventIngestionDeadLetterBinding(
      Queue eventIngestionDeadLetterQueue,
      DirectExchange eventsDeadLetterExchange,
      @Value("${hookrelay.rabbitmq.dead-letter-routing-key}") String routingKey) {
    return BindingBuilder.bind(eventIngestionDeadLetterQueue)
        .to(eventsDeadLetterExchange)
        .with(routingKey);
  }
}
