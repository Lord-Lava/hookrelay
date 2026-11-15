package com.hookrelay.event.api;

import com.hookrelay.auth.TenantContext;
import com.hookrelay.event.application.EventIngestionService;
import com.hookrelay.event.application.EventIngestionService.AcceptedEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
public class EventController {
  private final EventIngestionService eventIngestionService;

  public EventController(EventIngestionService eventIngestionService) {
    this.eventIngestionService = eventIngestionService;
  }

  @PostMapping
  public ResponseEntity<AcceptedEventResponse> ingest(
      @Valid @RequestBody CreateEventRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    AcceptedEvent accepted =
        eventIngestionService.accept(TenantContext.require().projectId(), request, idempotencyKey);

    AcceptedEventResponse body =
        new AcceptedEventResponse(accepted.eventId(), accepted.createdAt(), "ACCEPTED");
    return ResponseEntity.accepted()
        .header("Idempotent-Replayed", Boolean.toString(accepted.replayed()))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(body);
  }
}
