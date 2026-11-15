package com.hookrelay.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hookrelay.event.api.CreateEventRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventIngestionContractTest {
  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @AfterAll
  static void closeValidatorFactory() {
    VALIDATOR_FACTORY.close();
  }

  @Test
  void acceptsLowercaseEventTypeAndObjectPayload() throws Exception {
    CreateEventRequest request =
        new CreateEventRequest(
            "payment.succeeded", objectMapper.readTree("{\"paymentId\":\"pay_123\"}"));
    assertThat(VALIDATOR.validate(request)).isEmpty();
  }

  @Test
  void rejectsUppercaseEventType() throws Exception {
    CreateEventRequest request =
        new CreateEventRequest(
            "Payment.Succeeded", objectMapper.readTree("{\"paymentId\":\"pay_123\"}"));
    assertThat(VALIDATOR.validate(request)).isNotEmpty();
  }

  @Test
  void rejectsArrayPayload() throws Exception {
    CreateEventRequest request =
        new CreateEventRequest("payment.succeeded", objectMapper.readTree("[1,2,3]"));
    assertThat(VALIDATOR.validate(request))
        .anyMatch(v -> v.getMessage().equals("must be a JSON object"));
  }
}
