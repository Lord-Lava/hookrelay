package com.hookrelay.event.application;

import com.hookrelay.auth.ApiKeyAuthService;
import com.hookrelay.event.api.CreateEventRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class EventIngestionService {
  private static final String INSERT_EVENT_SQL =
      """
      INSERT INTO events (id, project_id, event_type, payload, idempotency_key, request_fingerprint, created_at)
      VALUES (?, ?, ?, ?::jsonb, ?, ?, now())
      ON CONFLICT (project_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
      RETURNING id, created_at, request_fingerprint
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final int maxPayloadBytes;

  public EventIngestionService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      @Value("${hookrelay.events.max-payload-bytes:262144}") int maxPayloadBytes) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.maxPayloadBytes = maxPayloadBytes;
  }

  @Transactional
  public AcceptedEvent accept(UUID projectId, CreateEventRequest request, String idempotencyKey) {
    validateIdempotencyKey(idempotencyKey);

    JsonNode canonicalData = canonicalize(request.data());
    requireNoNullBytes(canonicalData);
    String payloadJson = writeJson(canonicalData);
    if (payloadJson.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
      throw new IllegalArgumentException(
          "data exceeds the maximum payload size of " + maxPayloadBytes + " bytes");
    }

    String fingerprint = ApiKeyAuthService.sha256(request.eventType() + "\n" + payloadJson);
    UUID eventId = UUID.randomUUID();
    List<EventRow> inserted =
        jdbcTemplate.query(
            INSERT_EVENT_SQL,
            (rs, rowNum) ->
                new EventRow(
                    rs.getObject("id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("request_fingerprint").trim()),
            eventId,
            projectId,
            request.eventType(),
            payloadJson,
            idempotencyKey,
            fingerprint);

    if (inserted.isEmpty()) {
      EventRow existing = findExisting(projectId, idempotencyKey);
      if (!constantTimeEquals(existing.fingerprint(), fingerprint)) {
        throw new IdempotencyConflictException();
      }
      return new AcceptedEvent(existing.id(), existing.createdAt(), true);
    }

    EventRow created = inserted.getFirst();
    EventIngestedMessage message =
        new EventIngestedMessage(
            created.id(), projectId, request.eventType(), canonicalData, created.createdAt());
    jdbcTemplate.update(
        """
        INSERT INTO outbox_messages (id, project_id, aggregate_id, message_type, payload)
        VALUES (?, ?, ?, 'EVENT_INGESTED', ?::jsonb)
        """,
        UUID.randomUUID(),
        projectId,
        created.id(),
        writeJson(message));

    return new AcceptedEvent(created.id(), created.createdAt(), false);
  }

  private EventRow findExisting(UUID projectId, String idempotencyKey) {
    try {
      return jdbcTemplate.queryForObject(
          """
          SELECT id, created_at, request_fingerprint
          FROM events
          WHERE project_id = ? AND idempotency_key = ?
          """,
          (rs, rowNum) ->
              new EventRow(
                  rs.getObject("id", UUID.class),
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getString("request_fingerprint").trim()),
          projectId,
          idempotencyKey);
    } catch (EmptyResultDataAccessException exception) {
      throw new IllegalStateException(
          "Idempotency conflict was reported but the original event was not found", exception);
    }
  }

  private void validateIdempotencyKey(String key) {
    if (key == null) {
      return;
    }
    if (key.isBlank() || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
      throw new IllegalArgumentException(
          "Idempotency-Key must be 1-128 characters using letters, numbers, dot, underscore, colon or hyphen");
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      ObjectNode objectNode = (ObjectNode) node;
      ObjectNode sorted = objectMapper.createObjectNode();
      objectNode.properties().stream()
          .map(Map.Entry::getKey)
          .sorted(Comparator.naturalOrder())
          .forEach(name -> sorted.set(name, canonicalize(objectNode.get(name))));
      return sorted;
    }
    if (node.isArray()) {
      var array = objectMapper.createArrayNode();
      node.forEach(value -> array.add(canonicalize(value)));
      return array;
    }
    return node.deepCopy();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize event data", exception);
    }
  }

  private boolean constantTimeEquals(String left, String right) {
    return java.security.MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private static void requireNoNullBytes(JsonNode node) {
    if (node.isTextual() && node.asString().indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("Request data must not contain NUL bytes");
    }
    if (node.isObject() || node.isArray()) {
      node.forEach(EventIngestionService::requireNoNullBytes);
    }
  }

  private record EventRow(UUID id, Instant createdAt, String fingerprint) {}

  public record AcceptedEvent(UUID eventId, Instant createdAt, boolean replayed) {}

  public record EventIngestedMessage(
      UUID eventId, UUID projectId, String eventType, JsonNode data, Instant createdAt) {}
}
