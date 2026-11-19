package com.hookrelay.delivery.application;

import com.hookrelay.delivery.domain.DeliveryPolicy;
import com.hookrelay.delivery.repository.FanoutStore;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventFanoutService {
  public enum Result {
    CREATED,
    DUPLICATE
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final FanoutStore fanouts;
  private final FanoutProperties properties;
  private final TransactionTemplate transactions;

  public EventFanoutService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      FanoutStore fanouts,
      PlatformTransactionManager manager,
      FanoutProperties properties) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.fanouts = fanouts;
    this.properties = properties;
    transactions = new TransactionTemplate(manager);
    // The method must return only after its own commit, even if called with an ambient transaction.
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
  }

  public Result process(EventNotification notification) {
    return transactions.execute(tx -> {
      jdbc.execute("SET LOCAL lock_timeout = '2s'");
      jdbc.execute("SET LOCAL statement_timeout = '10s'");
      Integer matched =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM outbox_messages WHERE project_id=? AND id=?
                AND aggregate_id=? AND message_type='EVENT_INGESTED'
              """,
              Integer.class,
              notification.projectId(),
              notification.outboxId(),
              notification.eventId());
      if (matched != 1)
        throw new RejectedNotification(RejectedNotification.Reason.UNKNOWN_IDENTITY);
      var events =
          jdbc.query(
              """
              SELECT event_type,payload::text,created_at FROM events WHERE project_id=? AND id=? FOR UPDATE
              """,
              (rs, row) ->
                  new StoredEvent(
                      rs.getString("event_type"),
                      rs.getString("payload"),
                      rs.getTimestamp("created_at").toInstant()),
              notification.projectId(),
              notification.eventId());
      if (events.isEmpty())
        throw new RejectedNotification(RejectedNotification.Reason.UNKNOWN_IDENTITY);
      var event = events.getFirst();
      if (!event.type().equals(notification.eventType())
          || !event.createdAt().equals(notification.createdAt()))
        throw new RejectedNotification(RejectedNotification.Reason.METADATA_MISMATCH);
      if (fanouts.find(notification.projectId(), notification.eventId()).isPresent())
        return Result.DUPLICATE;
      if (properties.acceptEventsFrom() == null
          || event.createdAt().isBefore(properties.acceptEventsFrom()))
        throw new RejectedNotification(RejectedNotification.Reason.HISTORICAL_EVENT);
      var body = mapper.createObjectNode();
      body.put("eventId", notification.eventId().toString());
      body.put("eventType", event.type());
      body.put("createdAt", event.createdAt().toString());
      body.set("data", mapper.readTree(event.payload()));
      fanouts.createSubscribed(
          notification.projectId(),
          notification.eventId(),
          event.type(),
          mapper.writeValueAsBytes(body),
          new DeliveryPolicy("v1", 8, Duration.ofHours(24)));
      return Result.CREATED;
    });
  }

  private record StoredEvent(String type, String payload, Instant createdAt) {}
}
