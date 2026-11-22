package com.hookrelay.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.delivery.domain.Delivery;
import com.hookrelay.delivery.domain.DeliveryAttempt;
import com.hookrelay.delivery.domain.DeliveryStatus;
import com.hookrelay.delivery.domain.FanoutSnapshot;
import com.hookrelay.delivery.repository.DeliveryStore;
import com.hookrelay.delivery.repository.FanoutStore;
import com.hookrelay.delivery.signing.WebhookSigner;
import com.hookrelay.delivery.transport.SsrfSafeWebhookTransport;
import com.hookrelay.delivery.transport.TransportException;
import com.hookrelay.delivery.transport.TransportResult;
import com.hookrelay.delivery.transport.WebhookTransport;
import com.hookrelay.delivery.transport.WebhookTransportProperties;
import com.hookrelay.endpoint.application.EndpointService;
import com.hookrelay.security.ssrf.DefaultSsrfUrlValidator;
import com.hookrelay.security.ssrf.DnsResolver;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.io.EOFException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    Instant retryAfter = Instant.now().plusSeconds(60);
    when(transport.send(any(String.class), any(byte[].class), anyMap()))
        .thenReturn(
            new TransportResult(
                500,
                "error",
                Instant.now(),
                AttemptOutcome.RETRYABLE_FAILURE,
                100L,
                "HTTP_5XX",
                retryAfter));
    Instant retryAt = retryAfter.plusSeconds(10);
    when(retryScheduler.nextAttemptAt(eq(1), notNull(), any(Instant.class))).thenReturn(retryAt);
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    DeliveryStatus result = worker.process(projectId, deliveryId, leaseToken);

    assertThat(result).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    var savedRetryAt = ArgumentCaptor.forClass(Instant.class);
    verify(deliveryStore)
        .finishAttempt(
            eq(projectId),
            eq(deliveryId),
            eq(leaseToken),
            eq(attemptId),
            eq(AttemptOutcome.RETRYABLE_FAILURE),
            eq(500),
            eq(100L),
            eq("HTTP_5XX"),
            savedRetryAt.capture());
    assertThat(savedRetryAt.getValue()).isEqualTo(retryAt);
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
    when(transport.send(any(String.class), any(byte[].class), anyMap()))
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
    stubAttempt(DeliveryStatus.DEAD_LETTERED);
    stubTransportFailure(new SsrfValidationException("prohibited address"));

    assertPermanentFailure("SSRF_REJECTED");
  }

  @Test
  void givenHostnameMismatch_whenProcess_thenDeadLetteredNotRetried() throws Exception {
    stubAttempt(DeliveryStatus.DEAD_LETTERED);
    stubTransportFailure(new SSLPeerUnverifiedException("hostname mismatch"));

    assertPermanentFailure("TLS_FAILURE");
  }

  @Test
  void givenUntrustedCertificate_whenProcess_thenDeadLetteredNotRetried() throws Exception {
    stubAttempt(DeliveryStatus.DEAD_LETTERED);
    var failure = new SSLHandshakeException("certificate failed");
    failure.initCause(new CertificateException("untrusted", new EOFException()));
    stubTransportFailure(failure);

    assertPermanentFailure("TLS_FAILURE");
  }

  @Test
  void givenUnknownHost_whenProcess_thenDeadLetteredNotRetried() throws Exception {
    stubAttempt(DeliveryStatus.DEAD_LETTERED, "https://customer.example/hook");
    var actualTransport = stubDnsFailure(new UnknownHostException("no such host"));
    try {
      assertPermanentFailure("DNS_FAILURE");
    } finally {
      actualTransport.destroy();
    }
  }

  @Test
  void givenDnsLookupTimeout_whenProcess_thenRetryScheduled() throws Exception {
    stubAttempt(DeliveryStatus.RETRY_SCHEDULED, "https://customer.example/hook");
    var failure = new UnknownHostException("DNS lookup timed out");
    failure.initCause(new TimeoutException());
    var actualTransport = stubDnsFailure(failure);
    try {
      assertRetryableFailure("DNS_TIMEOUT");
    } finally {
      actualTransport.destroy();
    }
  }

  @Test
  void givenPeerEofDuringTlsHandshake_whenProcess_thenRetryScheduled() throws Exception {
    stubAttempt(DeliveryStatus.RETRY_SCHEDULED);
    var failure = new SSLHandshakeException("Remote host terminated the handshake");
    failure.initCause(new EOFException());
    stubTransportFailure(new IllegalStateException("TLS upgrade failed", failure));

    assertRetryableFailure("TRANSPORT_FAILURE");
  }

  @Test
  void givenIoFailure_whenProcess_thenRetryScheduledWithElapsedDuration() throws Exception {
    stubAttempt(DeliveryStatus.RETRY_SCHEDULED);
    stubTransportFailure(new IOException("connection reset"));

    assertRetryableFailure("TRANSPORT_FAILURE");
  }

  @Test
  void givenTransportDuration_whenSendSucceeds_thenExactDurationIsStored() throws Exception {
    stubAttempt(DeliveryStatus.DELIVERED);
    when(transport.send(any(String.class), any(byte[].class), anyMap()))
        .thenReturn(
            new TransportResult(
                200, "ok", Instant.now(), AttemptOutcome.SUCCEEDED, 73L, null, null));

    assertThat(worker.process(projectId, deliveryId, leaseToken))
        .isEqualTo(DeliveryStatus.DELIVERED);

    verify(deliveryStore)
        .finishAttempt(
            eq(projectId),
            eq(deliveryId),
            eq(leaseToken),
            eq(attemptId),
            eq(AttemptOutcome.SUCCEEDED),
            eq(200),
            eq(73L),
            isNull(),
            isNull());
  }

  private void stubAttempt(DeliveryStatus status) {
    stubAttempt(status, "https://8.8.8.8/hook");
  }

  private void stubAttempt(DeliveryStatus status, String destinationUrl) {
    var delivery = pendingDelivery(destinationUrl);
    when(deliveryStore.find(projectId, deliveryId))
        .thenReturn(Optional.of(delivery))
        .thenReturn(Optional.of(Delivery.builder().status(status).build()));
    when(fanoutStore.find(projectId, eventId)).thenReturn(Optional.of(snapshot()));
    when(endpointService.getSigningSecret(endpointId, projectId)).thenReturn("whsec_dGVzdA==");
    when(signer.sign(any(UUID.class), any(Long.class), any(byte[].class), any(String.class)))
        .thenReturn("v1,sig");
    when(deliveryStore.startAttempt(projectId, deliveryId, leaseToken))
        .thenReturn(Optional.of(startedAttempt()));
    when(deliveryStore.finishAttempt(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);
  }

  private void stubTransportFailure(Throwable cause) throws TransportException {
    when(transport.send(any(String.class), any(byte[].class), anyMap()))
        .thenThrow(new TransportException("Send failed", cause));
  }

  private SsrfSafeWebhookTransport stubDnsFailure(UnknownHostException failure)
      throws TransportException {
    DnsResolver resolver =
        host -> {
          throw failure;
        };
    var actualTransport =
        new SsrfSafeWebhookTransport(
            new DefaultSsrfUrlValidator(resolver),
            resolver,
            new WebhookTransportProperties(null, null, null, 0, 0, 0));
    when(transport.send(any(String.class), any(byte[].class), anyMap()))
        .thenAnswer(call ->
            actualTransport.send(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    return actualTransport;
  }

  private void assertPermanentFailure(String expectedErrorCode) {
    assertThat(worker.process(projectId, deliveryId, leaseToken))
        .isEqualTo(DeliveryStatus.DEAD_LETTERED);

    var outcome = ArgumentCaptor.forClass(AttemptOutcome.class);
    var httpStatus = ArgumentCaptor.forClass(Integer.class);
    var errorCode = ArgumentCaptor.forClass(String.class);
    var retryAt = ArgumentCaptor.forClass(Instant.class);
    verify(deliveryStore)
        .finishAttempt(
            eq(projectId),
            eq(deliveryId),
            eq(leaseToken),
            eq(attemptId),
            outcome.capture(),
            httpStatus.capture(),
            anyLong(),
            errorCode.capture(),
            retryAt.capture());
    assertThat(outcome.getValue()).isEqualTo(AttemptOutcome.PERMANENT_FAILURE);
    assertThat(httpStatus.getValue()).isNull();
    assertThat(errorCode.getValue()).isEqualTo(expectedErrorCode);
    assertThat(retryAt.getValue()).isNull();
    verifyNoInteractions(retryScheduler);
  }

  private void assertRetryableFailure(String expectedErrorCode) {
    Instant retryAt = Instant.now().plusSeconds(10);
    when(retryScheduler.nextAttemptAt(eq(1), isNull(), any(Instant.class))).thenReturn(retryAt);

    long startedNanos = System.nanoTime();
    assertThat(worker.process(projectId, deliveryId, leaseToken))
        .isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

    var duration = ArgumentCaptor.forClass(Long.class);
    verify(deliveryStore)
        .finishAttempt(
            eq(projectId),
            eq(deliveryId),
            eq(leaseToken),
            eq(attemptId),
            eq(AttemptOutcome.RETRYABLE_FAILURE),
            isNull(),
            duration.capture(),
            eq(expectedErrorCode),
            eq(retryAt));
    assertThat(duration.getValue()).isBetween(0L, elapsedMs);
  }

  private Delivery pendingDelivery() {
    return pendingDelivery("https://8.8.8.8/hook");
  }

  private Delivery pendingDelivery(String destinationUrl) {
    return Delivery.builder()
        .id(deliveryId)
        .projectId(projectId)
        .eventId(eventId)
        .endpointId(endpointId)
        .destinationUrl(destinationUrl)
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
