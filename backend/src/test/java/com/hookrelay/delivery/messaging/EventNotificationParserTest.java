package com.hookrelay.delivery.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.delivery.application.FanoutProperties;
import com.hookrelay.delivery.application.RejectedNotification;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

class EventNotificationParserTest {
  private final EventNotificationParser parser =
      new EventNotificationParser(JsonMapper.builder().build());
  private final String body =
      """
      {"eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","projectId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
       "eventType":"test.created","createdAt":"2026-11-20T00:00:00Z","data":{}}
      """;

  private Message message(String json) {
    var props = new MessageProperties();
    props.setContentType("application/json");
    props.setMessageId(UUID.randomUUID().toString());
    return new Message(json.getBytes(StandardCharsets.UTF_8), props);
  }

  @Test
  void distinguishesTransientDatabaseFailuresFromPoisonInvariants() {
    for (String state :
        List.of("08006", "40001", "40P01", "55P03", "57014", "53300", "57P01", "57P02", "57P03"))
      assertThat(
              FanoutConsumer.retryable(
                  new org.springframework.jdbc.UncategorizedSQLException(
                      "test", "SELECT synthetic", new java.sql.SQLException("synthetic", state))))
          .isTrue();
    for (String state : List.of("23505", "23503", "42601"))
      assertThat(
              FanoutConsumer.retryable(
                  new org.springframework.jdbc.UncategorizedSQLException(
                      "test", "SELECT synthetic", new java.sql.SQLException("synthetic", state))))
          .isFalse();
    assertThat(FanoutConsumer.retryable(new IllegalStateException("synthetic"))).isFalse();
  }

  @Test
  void acceptsTheExistingUnversionedPublisherEnvelope() {
    var input = message(body);
    var parsed = parser.parse(input);
    assertThat(parsed.outboxId().toString()).isEqualTo(input.getMessageProperties().getMessageId());
    assertThat(parsed.eventType()).isEqualTo("test.created");
  }

  @Test
  void rejectsDuplicateKeysTrailingDocumentsAndInvalidSchema() {
    for (String invalid :
        List.of(
            body + "{}",
            body.replace("\"eventType\":", "\"eventType\":\"bad\",\"eventType\":"),
            body.replace("\"data\":{}", "\"data\":[]"),
            body.replace("2026-11-20T00:00:00Z", "not-a-date"),
            body.replace("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "a-a-a-a-a"),
            "null",
            "[]",
            "{}"))
      assertThatThrownBy(() -> parser.parse(message(invalid)))
          .isInstanceOf(RejectedNotification.class)
          .hasMessage("MALFORMED");
  }

  @Test
  void rejectsMissingMessageIdUnsupportedContentTypeAndOversizeBeforeParsing() {
    var noId = message(body);
    noId.getMessageProperties().setMessageId(null);
    assertThatThrownBy(() -> parser.parse(noId)).hasMessage("MALFORMED");
    var wrongType = message(body);
    wrongType.getMessageProperties().setContentType("application/x-java-serialized-object");
    assertThatThrownBy(() -> parser.parse(wrongType)).hasMessage("MALFORMED");
    assertThatThrownBy(() ->
            parser.parse(
                new Message(new byte[1024 * 1024 + 1], message(body).getMessageProperties())))
        .hasMessage("TOO_LARGE");
  }

  @Test
  void activationRequiresReviewedCutoffAndBoundedConcurrency() {
    assertThatThrownBy(() -> new FanoutProperties(true, false, Instant.now(), 2, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FanoutProperties(true, true, null, 2, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FanoutProperties(false, false, null, 9, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FanoutProperties(false, false, null, 2, 51))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new FanoutProperties(false, false, null, 2, 10).enabled()).isFalse();
  }
}
