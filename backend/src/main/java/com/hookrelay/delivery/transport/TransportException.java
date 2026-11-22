package com.hookrelay.delivery.transport;

import com.hookrelay.security.ssrf.SsrfValidationException;
import java.io.EOFException;
import java.io.Serial;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Thrown when a webhook transport request cannot be sent safely or fails at the transport level.
 * Does not include HTTP response bodies or other potentially sensitive payload data.
 *
 * <p>Exceptions produced at the transport boundary carry an explicit {@link #failureCategory()} and
 * {@link #isPermanent()} flag so consumers (for example the delivery worker) classify the failure
 * once, at the boundary, instead of re-deriving it by walking the cause chain.
 */
public class TransportException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  private final boolean permanent;
  private final String failureCategory;

  public TransportException(String message) {
    this(message, null, false, null);
  }

  public TransportException(String message, Throwable cause) {
    this(message, cause, false, null);
  }

  public TransportException(
      String message, Throwable cause, boolean permanent, String failureCategory) {
    super(message, cause);
    this.permanent = permanent;
    this.failureCategory = failureCategory;
  }

  /** Whether the failure is permanent and must not be retried. */
  public boolean isPermanent() {
    return permanent;
  }

  /** Authoritative failure category assigned at the transport boundary, or {@code null}. */
  public String failureCategory() {
    return failureCategory;
  }

  /** Classifies a failure once at the transport boundary, preserving its original cause. */
  public static TransportException classify(String message, Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransportException transportFailure
          && transportFailure.failureCategory() != null) {
        return new TransportException(
            message, failure, transportFailure.isPermanent(), transportFailure.failureCategory());
      }
      // Registration validation wraps DNS failures to preserve its fail-closed API.
      if (cause instanceof SsrfValidationException
          && !(cause.getCause() instanceof UnknownHostException)) {
        return new TransportException(message, failure, true, "SSRF_REJECTED");
      }
      if (cause instanceof SSLException) {
        // JSSE uses SSLHandshakeException with an EOF cause for a peer closing mid-handshake.
        if (cause instanceof SSLHandshakeException
            && hasCause(cause, EOFException.class)
            && !hasCause(cause, CertificateException.class)
            && !hasCause(cause, CertPathValidatorException.class)
            && !hasCause(cause, SSLPeerUnverifiedException.class)) {
          break;
        }
        return new TransportException(message, failure, true, "TLS_FAILURE");
      }
      if (cause instanceof UnknownHostException) {
        if (hasCause(cause, InterruptedException.class)) {
          return new TransportException(message, failure, false, "DNS_INTERRUPTED");
        }
        if (hasCause(cause, TimeoutException.class)) {
          return new TransportException(message, failure, false, "DNS_TIMEOUT");
        }
        if (hasCause(cause, RejectedExecutionException.class)) {
          return new TransportException(message, failure, false, "DNS_CAPACITY_UNAVAILABLE");
        }
        return new TransportException(message, failure, true, "DNS_FAILURE");
      }
    }
    return new TransportException(message, failure, false, "TRANSPORT_FAILURE");
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }
}
