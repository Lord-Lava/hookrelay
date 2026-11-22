package com.hookrelay.delivery.api;

import com.hookrelay.delivery.application.DeliveryReplayService;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/deliveries")
public class DeliveryReplayController {

  private final DeliveryReplayService replayService;

  public DeliveryReplayController(DeliveryReplayService replayService) {
    this.replayService = replayService;
  }

  /**
   * Replays a dead-lettered delivery. Creates a new delivery cycle with a fresh bounded retry
   * budget; all previous attempts are preserved. Idempotent when called with the same
   * Idempotency-Key.
   */
  @PostMapping("/{deliveryId}/replay")
  public ResponseEntity<ReplayResponse> replay(
      @PathVariable UUID deliveryId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    // Note: TenantContext is set by the authentication filter before the controller is invoked.
    DeliveryReplayService.ReplayResult result =
        replayService.replay(
            com.hookrelay.auth.TenantContext.require().projectId(),
            deliveryId,
            com.hookrelay.auth.TenantContext.require().apiKeyId(),
            idempotencyKey);
    return ResponseEntity.accepted()
        .body(
            ReplayResponse.builder()
                .deliveryId(result.deliveryId())
                .newCycleNumber(result.newCycleNumber())
                .replayed(result.replayed())
                .replayedAt(Instant.now())
                .build());
  }

  /** Response for a delivery replay request. */
  @Builder
  public record ReplayResponse(
      UUID deliveryId, int newCycleNumber, boolean replayed, Instant replayedAt) {}
}
