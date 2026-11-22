package com.hookrelay.delivery.transport;

/**
 * Thrown when a webhook transport request cannot be sent safely or fails at the transport level.
 * Does not include HTTP response bodies or other potentially sensitive payload data.
 *
 * <p>Exceptions produced at the transport boundary carry an explicit {@link #failureCategory()} and
 * {@link #isPermanent()} flag so consumers (for example the delivery worker) classify the failure
 * once, at the boundary, instead of re-deriving it by walking the cause chain.
 */
import java.io.Serial;

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
}
