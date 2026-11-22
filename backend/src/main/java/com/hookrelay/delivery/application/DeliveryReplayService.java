package com.hookrelay.delivery.application;

import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service for manually replaying dead-lettered deliveries.
 *
 * <p>Replay transitions a {@link DeliveryStatus#DEAD_LETTERED} delivery back to {@link
 * DeliveryStatus#PENDING} by creating a new delivery cycle with a fresh bounded retry budget. All
 * previous cycles and attempts are preserved. Replay is idempotent when called with the same
 * idempotency key.
 *
 * <p>No outbound HTTP is initiated from this service; the replayed delivery is picked up by the
 * existing {@link com.hookrelay.delivery.application.DeliveryDispatcher} and processed by {@link
 * com.hookrelay.delivery.application.DeliveryWorker}.
 */
@Service
public class DeliveryReplayService {

  private final DeliveryStore deliveryStore;
  private final EndpointService endpointService;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public DeliveryReplayService(
      DeliveryStore deliveryStore,
      EndpointService endpointService,
      JdbcTemplate jdbc,
      TransactionTemplate transactions) {
    this.deliveryStore = deliveryStore;
    this.endpointService = endpointService;
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /**
   * Replays a dead-lettered delivery.
   *
   * @param projectId the authenticated project
   * @param deliveryId the delivery to replay
   * @param actorApiKeyId the API key used for the replay (audit only)
   * @param idempotencyKey client-supplied idempotency key
   * @return the replay result
   * @throws DeliveryNotFoundException if the delivery or its endpoint is unknown or cross-tenant
   * @throws InvalidDeliveryStateException if the delivery is not DEAD_LETTERED
   * @throws EndpointDisabledException if the endpoint is disabled
   */
  public ReplayResult replay(
      UUID projectId, UUID deliveryId, UUID actorApiKeyId, String idempotencyKey) {
    // --- Read-only validation (outside the write transaction) ---
    Delivery delivery =
        deliveryStore
            .find(projectId, deliveryId)
            .orElseThrow(() -> new DeliveryNotFoundException("Delivery not found: " + deliveryId));

    WebhookEndpoint endpoint;
    try {
      endpoint = endpointService.getEndpoint(delivery.endpointId(), projectId);
    } catch (com.hookrelay.endpoint.application.EndpointNotFoundException ex) {
      throw new DeliveryNotFoundException(
          "Endpoint for delivery " + deliveryId + " is not found or deleted");
    }

    if (endpoint.deletedAt() != null) {
      throw new DeliveryNotFoundException(
          "Endpoint for delivery " + deliveryId + " has been deleted");
    }

    if (!endpoint.enabled()) {
      throw new EndpointDisabledException("Endpoint " + delivery.endpointId() + " is disabled");
    }

    // Verify the signing secret is available and decryptable; never expose plaintext.
    endpointService.getSigningSecret(delivery.endpointId(), projectId);

    String fingerprint = sha256(idempotencyKey);

    // --- Atomic replay + audit in a single transaction ---
    return transactions.execute(tx -> {
      // Re-verify delivery exists under lock (optimistic concurrency control).
      Delivery current =
          deliveryStore
              .find(projectId, deliveryId)
              .orElseThrow(
                  () -> new DeliveryNotFoundException("Delivery not found: " + deliveryId));

      // Idempotency: same replay_fingerprint already used → already replayed.
      // Check before status so that a replayed (PENDING) delivery is still recognized.
      List<Integer> existingCycles =
          jdbc.query(
              """
              SELECT cycle_number FROM delivery_cycles
              WHERE project_id=? AND delivery_id=? AND replay_fingerprint=?
              """,
              (rs, row) -> rs.getInt("cycle_number"),
              projectId,
              deliveryId,
              fingerprint);
      Integer existingCycle = existingCycles.isEmpty() ? null : existingCycles.get(0);
      if (existingCycle != null) {
        return new ReplayResult(deliveryId, existingCycle, true);
      }

      if (current.status() != DeliveryStatus.DEAD_LETTERED) {
        throw new InvalidDeliveryStateException(
            "Delivery " + deliveryId + " is " + current.status() + ", not DEAD_LETTERED");
      }

      // Replay: insert new cycle + transition delivery to PENDING.
      boolean replayed;
      try {
        replayed =
            deliveryStore.replay(
                projectId, deliveryId, actorApiKeyId, idempotencyKey, fingerprint, "MANUAL_REPLAY");
      } catch (DataIntegrityViolationException ex) {
        // Concurrent transaction already inserted this replay_key. Treat as idempotent success.
        List<Integer> concurrentCycles =
            jdbc.query(
                """
                SELECT cycle_number FROM delivery_cycles
                WHERE project_id=? AND delivery_id=? AND replay_fingerprint=?
                """,
                (rs, row) -> rs.getInt("cycle_number"),
                projectId,
                deliveryId,
                fingerprint);
        Integer concurrentCycle = concurrentCycles.isEmpty() ? null : concurrentCycles.get(0);
        if (concurrentCycle != null) {
          return new ReplayResult(deliveryId, concurrentCycle, true);
        }
        throw ex;
      }

      if (!replayed) {
        throw new InvalidDeliveryStateException(
            "Delivery " + deliveryId + " is no longer replayable");
      }

      // Record audit event atomically with the replay.
      jdbc.update(
          """
          INSERT INTO delivery_replay_audit_events
            (id, project_id, delivery_id, previous_cycle_number, new_cycle_number,
             actor_api_key_id, idempotency_key_fingerprint, action, created_at)
          VALUES (?,?,?,?,?,?,?,?,clock_timestamp())
          """,
          UUID.randomUUID(),
          projectId,
          deliveryId,
          current.cycleNumber(),
          current.cycleNumber() + 1,
          actorApiKeyId,
          fingerprint,
          "REPLAYED");

      return new ReplayResult(deliveryId, current.cycleNumber() + 1, true);
    });
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Result of a replay operation. */
  public record ReplayResult(UUID deliveryId, int newCycleNumber, boolean replayed) {}
}
