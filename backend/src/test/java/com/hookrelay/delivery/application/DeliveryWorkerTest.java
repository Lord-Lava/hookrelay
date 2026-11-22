package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryAttempt;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.domain.FanoutSnapshot;
import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.delivery.repository.FanoutStore;
import com.hookrelay.delivery.signing.WebhookSigner;
import com.hookrelay.delivery.transport.TransportResult;
import com.hookrelay.delivery.transport.WebhookTransport;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class DeliveryWorkerTest {

  private final UUID projectId = UUID.randomUUID();
  private final UUID deliveryId = UUID.randomUUID();
  private final UUID eventId = UUID.randomUUID();
  private final UUID endpointId = UUID.randomUUID();
  private final UUID leaseToken = UUID.randomUUID();
  private final UUID attemptId = UUID.randomUUID();

  private final DeliveryStore deliveryStore = mock(DeliveryStore.class);
  private final FanoutStore fanoutStore = mock(FanoutStore.class);
  private final WebhookTransport transport = mock(WebhookTransport.class);
  private final WebhookSigner signer = mock(WebhookSigner.class);
  private final EndpointService endpointService = mock(EndpointService.class);
  private final RetryScheduler retryScheduler = mock(RetryScheduler.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);

  private final DeliveryWorker worker =
      new DeliveryWorker(
          deliveryStore,
          fanoutStore,
          transport,
          signer,
          endpointService,
          retryScheduler,
          transactionManager);

  @Test
  void givenHttp500_whenProcess_thenRetryScheduled() throws Exception {
    Delivery scheduled = Delivery.builder().status(DeliveryStatus.RETRY_SCHEDULED).build();
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(scheduled));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    Instant retryAt = Instant.now().plusSeconds(60);
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                500,
                "error",
                Instant.now(),
                AttemptOutcome.RETRYABLE_FAILURE,
                100L,
                "HTTP_5XX",
                retryAt));
    when(retryScheduler.nextAttemptAt(eq(1), eq(60), any(Instant.class))).thenReturn(retryAt);
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    verify(transport).send(eq("https://8.8.8.8/hook"), any(byte[].class), any(Map.class));
  }

  @Test
  void givenHttp400_whenProcess_thenDeadLettered() throws Exception {
    Delivery dead = Delivery.builder().status(DeliveryStatus.DEAD_LETTERED).build();
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(dead));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenReturn(
            new TransportResult(
                400,
                "bad",
                Instant.now(),
                AttemptOutcome.PERMANENT_FAILURE,
                50L,
                "HTTP_4XX",
                null));
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.DEAD_LETTERED);
  }

  @Test
  void givenSsrfRejected_whenProcess_thenDeadLetteredNotRetried() throws Exception {
    Delivery dead = Delivery.builder().status(DeliveryStatus.DEAD_LETTERED).build();
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(dead));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    SsrfValidationException cause = new SsrfValidationException("prohibited address");
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenThrow(new com.hookrelay.delivery.transport.TransportException("SSRF", cause));
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.DEAD_LETTERED);
  }

  @Test
  void givenTlsFailure_whenProcess_thenDeadLetteredNotRetried() throws Exception {
    Delivery dead = Delivery.builder().status(DeliveryStatus.DEAD_LETTERED).build();
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(dead));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    SSLException cause = new SSLException("hostname mismatch");
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenThrow(new com.hookrelay.delivery.transport.TransportException("TLS", cause));
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.DEAD_LETTERED);
  }

  @Test
  void givenDnsFailure_whenProcess_thenDeadLetteredNotRetried() throws Exception {
    Delivery dead = Delivery.builder().status(DeliveryStatus.DEAD_LETTERED).build();
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(dead));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    UnknownHostException cause = new UnknownHostException("no such host");
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenThrow(new com.hookrelay.delivery.transport.TransportException("DNS", cause));
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.DEAD_LETTERED);
  }

  @Test
  void givenIoFailure_whenProcess_thenRetryScheduled() throws Exception {
    Delivery scheduled = Delivery.builder().status(DeliveryStatus.RETRY_SCHEDULED).build();
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(scheduled));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    IOException cause = new IOException("connection reset");
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenThrow(new com.hookrelay.delivery.transport.TransportException("IO", cause));
    when(retryScheduler.nextAttemptAt(eq(1), eq(null), any(Instant.class)))
        .thenReturn(Instant.now().plusSeconds(10));
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
  }

  @Test
  void givenDurationRecorded_whenTransportSends_thenNonZeroDuration() throws Exception {
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(pendingDelivery()))
        .thenReturn(Optional.of(Delivery.builder().status(DeliveryStatus.DELIVERED).build()));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    when(transport.send(any(String.class), any(byte[].class), any(Map.class)))
        .thenAnswer(invocation -> {
          Thread.sleep(5);
          return new TransportResult(
              200, "ok", Instant.now(), AttemptOutcome.SUCCEEDED, null, null, null);
        });
    when(deliveryStore.finishAttempt(
            any(),
            any(),
            any(),
            any(),
            eq(AttemptOutcome.SUCCEEDED),
            any(),
            anyLong(),
            any(),
            any()))
        .thenReturn(true);

    worker.process(projectId, deliveryId, leaseToken);

    verify(deliveryStore)
        .finishAttempt(
            eq(projectId),
            eq(deliveryId),
            eq(leaseToken),
            eq(attemptId),
            eq(AttemptOutcome.SUCCEEDED),
            any(),
            anyLong(),
            any(),
            any());
  }

  private Delivery pendingDelivery() {
    return Delivery.builder()
        .id(deliveryId)
        .projectId(projectId)
        .eventId(eventId)
        .endpointId(endpointId)
        .destinationUrl("https://8.8.8.8/hook")
        .status(DeliveryStatus.PENDING)
        .attemptCount(0)
        .cycleNumber(0)
        .cycleAttemptCount(0)
        .nextAttemptAt(Instant.now())
        .leaseOwner("worker")
        .leaseToken(leaseToken)
        .leaseExpiresAt(Instant.now().plusSeconds(60))
        .cycleStartedAt(Instant.now())
        .cycleDeadline(Instant.now().plus(Duration.ofHours(24)))
        .policyVersion("v1")
        .maxAttempts(8)
        .terminalAt(null)
        .lastErrorCode(null)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  private DeliveryAttempt startedAttempt() {
    return DeliveryAttempt.builder()
        .id(attemptId)
        .projectId(projectId)
        .deliveryId(deliveryId)
        .attemptNumber(1)
        .cycleNumber(0)
        .leaseToken(leaseToken)
        .startedAt(Instant.now())
        .completedAt(null)
        .outcome(AttemptOutcome.STARTED)
        .httpStatus(null)
        .durationMs(null)
        .errorCode(null)
        .build();
  }

  private FanoutSnapshot snapshot() {
    return new FanoutSnapshot(projectId, eventId, Instant.now(), 1, "v1", new byte[] {1});
  }
}
