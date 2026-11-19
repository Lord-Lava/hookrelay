package com.hookrelay.delivery.messaging;

import com.hookrelay.delivery.application.EventNotification;
import com.hookrelay.delivery.application.RejectedNotification;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class EventNotificationParser {
  private final ObjectReader reader;

  public EventNotificationParser(ObjectMapper mapper) {
    reader =
        mapper
            .reader()
            .with(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
  }

  public EventNotification parse(Message message) {
    if (message.getBody().length > 1024 * 1024)
      throw new RejectedNotification(RejectedNotification.Reason.TOO_LARGE);
    try {
      if (!"application/json".equals(message.getMessageProperties().getContentType()))
        throw new IllegalArgumentException();
      JsonNode root = reader.readTree(message.getBody());
      if (root == null || !root.isObject() || root.size() != 5 || !root.path("data").isObject())
        throw new IllegalArgumentException();
      Set<String> fields = Set.of("eventId", "projectId", "eventType", "createdAt", "data");
      if (root.properties().stream().anyMatch(e -> !fields.contains(e.getKey())))
        throw new IllegalArgumentException();
      String type = text(root, "eventType");
      if (type.isBlank() || type.length() > 128) throw new IllegalArgumentException();
      String created = text(root, "createdAt");
      if (created.length() > 40) throw new IllegalArgumentException();
      return new EventNotification(
          uuid(message.getMessageProperties().getMessageId()),
          uuid(text(root, "projectId")),
          uuid(text(root, "eventId")),
          type,
          Instant.parse(created));
    } catch (RuntimeException exception) {
      throw new RejectedNotification(RejectedNotification.Reason.MALFORMED);
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.path(field);
    if (!value.isString()) throw new IllegalArgumentException();
    return value.asString();
  }

  private static UUID uuid(String value) {
    if (value == null || value.length() != 36) throw new IllegalArgumentException();
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw new IllegalArgumentException();
    return id;
  }
}
