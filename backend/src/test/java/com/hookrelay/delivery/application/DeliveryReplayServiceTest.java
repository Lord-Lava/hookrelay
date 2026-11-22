package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.endpoint.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class DeliveryReplayServiceTest {

  private final UUID projectId = UUID.randomUUID();
  private final UUID deliveryId = UUID.randomUUID();
  private final UUID endpointId = UUID.randomUUID();
  private final UUID apiKeyId = UUID.randomUUID();
  private final String idempotencyKey = "replay-key-1";

  private DeliveryStore deliveryStore;
  private EndpointService endpointService;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  private DeliveryReplayService service;

  @BeforeEach
  void setUp() {
    deliveryStore = mock(DeliveryStore.class);
    endpointService = mock(EndpointService.class);
    jdbc = mock(JdbcTemplate.class);
    transactions = mock(TransactionTemplate.class);
    service = new DeliveryReplayService(deliveryStore, endpointService, jdbc, transactions);

    when(transactions.execute(any(TransactionCallback.class)))
        .thenAnswer(invocation -> {
          TransactionCallback<?> callback = invocation.getArgument(0);
          return callback.doInTransaction(null);
        });

    when(jdbc.queryForObject(
            anyString(), eq(Integer.class), eq(projectId), eq(deliveryId), anyString()))
        .thenReturn(null);
  }

  private Delivery deadLetteredDelivery() {
    Instant now = Instant.now();
    return Delivery.builder()
        .id(deliveryId)
        .projectId(projectId)
        .eventId(UUID.randomUUID())
        .endpointId(endpointId)
        .destinationUrl("https://8.8.8.8/hook")
        .status(DeliveryStatus.DEAD_LETTERED)
        .attemptCount(3)
        .cycleNumber(0)
        .cycleAttemptCount(3)
        .cycleStartedAt(now.minusSeconds(3600))
        .cycleDeadline(now.plusSeconds(3600))
        .policyVersion("v1")
        .maxAttempts(8)
        .terminalAt(now)
        .lastErrorCode("HTTP_5XX")
        .createdAt(now.minusSeconds(7200))
        .updatedAt(now)
        .build();
  }

  private WebhookEndpoint enabledEndpoint() {
    return WebhookEndpoint.builder()
        .id(endpointId)
        .projectId(projectId)
        .url("https://8.8.8.8/hook")
        .description("Test endpoint")
        .signingSecretCiphertext("ciphertext")
        .nonce(new byte[12])
        .keyVersion("v1")
        .enabled(true)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .deletedAt(null)
        .build();
  }

  @Test
  void givenDeadLetteredDelivery_whenReplay_thenCreatesNewCycleAndTransitionsToPending() {
    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(deadLetteredDelivery()));
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(enabledEndpoint());
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(deliveryStore.replay(
            eq(projectId),
            eq(deliveryId),
            eq(apiKeyId),
            eq(idempotencyKey),
            anyString(),
            eq("MANUAL_REPLAY")))
        .thenReturn(true);

    DeliveryReplayService.ReplayResult result =
        service.replay(projectId, deliveryId, apiKeyId, idempotencyKey);

    assertThat(result.deliveryId()).isEqualTo(deliveryId);
    assertThat(result.newCycleNumber()).isEqualTo(1);
    assertThat(result.replayed()).isTrue();
    verify(deliveryStore)
        .replay(
            eq(projectId),
            eq(deliveryId),
            eq(apiKeyId),
            eq(idempotencyKey),
            anyString(),
            eq("MANUAL_REPLAY"));
  }

  @Test
  void givenNonDeadLetteredDelivery_whenReplay_thenThrowsInvalidDeliveryState() {
    Delivery pending =
        Delivery.builder()
            .id(deliveryId)
            .projectId(projectId)
            .eventId(UUID.randomUUID())
            .endpointId(endpointId)
            .destinationUrl("https://8.8.8.8/hook")
            .status(DeliveryStatus.PENDING)
            .cycleNumber(0)
            .build();
    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(pending));
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(enabledEndpoint());

    assertThatThrownBy(() -> service.replay(projectId, deliveryId, apiKeyId, idempotencyKey))
        .isInstanceOf(InvalidDeliveryStateException.class);
  }

  @Test
  void givenUnknownDelivery_whenReplay_thenThrowsDeliveryNotFound() {
    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replay(projectId, deliveryId, apiKeyId, idempotencyKey))
        .isInstanceOf(DeliveryNotFoundException.class);
  }

  @Test
  void givenDeletedEndpoint_whenReplay_thenThrowsDeliveryNotFound() {
    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(deadLetteredDelivery()));
    WebhookEndpoint deleted =
        WebhookEndpoint.builder()
            .id(endpointId)
            .projectId(projectId)
            .url("https://8.8.8.8/hook")
            .enabled(true)
            .deletedAt(Instant.now())
            .build();
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(deleted);

    assertThatThrownBy(() -> service.replay(projectId, deliveryId, apiKeyId, idempotencyKey))
        .isInstanceOf(DeliveryNotFoundException.class);
  }

  @Test
  void givenDisabledEndpoint_whenReplay_thenThrowsEndpointDisabled() {
    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(deadLetteredDelivery()));
    WebhookEndpoint disabled =
        WebhookEndpoint.builder()
            .id(endpointId)
            .projectId(projectId)
            .url("https://8.8.8.8/hook")
            .enabled(false)
            .build();
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(disabled);

    assertThatThrownBy(() -> service.replay(projectId, deliveryId, apiKeyId, idempotencyKey))
        .isInstanceOf(EndpointDisabledException.class);
  }

  @Test
  void givenSameIdempotencyKey_whenReplayTwice_thenReturnsSameResult() {
    reset(deliveryStore, endpointService, jdbc, transactions);

    when(transactions.execute(any(TransactionCallback.class)))
        .thenAnswer(invocation -> {
          TransactionCallback<?> callback = invocation.getArgument(0);
          return callback.doInTransaction(null);
        });

    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(deadLetteredDelivery()));
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(enabledEndpoint());
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(deliveryStore.replay(
            eq(projectId),
            eq(deliveryId),
            eq(apiKeyId),
            eq(idempotencyKey),
            anyString(),
            eq("MANUAL_REPLAY")))
        .thenReturn(true);

    DeliveryReplayService.ReplayResult first =
        service.replay(projectId, deliveryId, apiKeyId, idempotencyKey);
    assertThat(first.replayed()).isTrue();
    assertThat(first.newCycleNumber()).isEqualTo(1);

    reset(jdbc);
    when(jdbc.queryForObject(
            anyString(), eq(Integer.class), eq(projectId), eq(deliveryId), anyString()))
        .thenReturn(1);

    DeliveryReplayService.ReplayResult second =
        service.replay(projectId, deliveryId, apiKeyId, idempotencyKey);
    assertThat(second.replayed()).isTrue();
    assertThat(second.newCycleNumber()).isEqualTo(1);
  }

  @Test
  void givenSigningUnavailable_whenReplay_thenThrowsSigningUnavailable() {
    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(deadLetteredDelivery()));
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(enabledEndpoint());
    when(endpointService.getSigningSecret(endpointId, projectId))
        .thenThrow(new com.hookrelay.security.SigningUnavailableException());

    assertThatThrownBy(() -> service.replay(projectId, deliveryId, apiKeyId, idempotencyKey))
        .isInstanceOf(com.hookrelay.security.SigningUnavailableException.class);
  }

  @Test
  void givenConcurrentReplay_whenDataIntegrityViolation_thenTreatsAsIdempotent() {
    reset(deliveryStore, endpointService, jdbc, transactions);

    when(transactions.execute(any(TransactionCallback.class)))
        .thenAnswer(invocation -> {
          TransactionCallback<?> callback = invocation.getArgument(0);
          return callback.doInTransaction(null);
        });

    when(deliveryStore.find(projectId, deliveryId)).thenReturn(Optional.of(deadLetteredDelivery()));
    when(endpointService.getEndpoint(endpointId, projectId)).thenReturn(enabledEndpoint());
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");

    when(deliveryStore.replay(
            eq(projectId),
            eq(deliveryId),
            eq(apiKeyId),
            eq(idempotencyKey),
            anyString(),
            eq("MANUAL_REPLAY")))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate key"));

    when(jdbc.query(
            anyString(),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(projectId),
            eq(deliveryId),
            anyString()))
        .thenReturn(java.util.List.of(2));

    DeliveryReplayService.ReplayResult result =
        service.replay(projectId, deliveryId, apiKeyId, idempotencyKey);
    assertThat(result.replayed()).isTrue();
    assertThat(result.newCycleNumber()).isEqualTo(2);
  }
}
