package com.hookrelay.delivery.application;

/** Bounded diagnostics only; never include broker bodies, arbitrary IDs or database errors. */
public class RejectedNotification extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Reason {
    MALFORMED,
    TOO_LARGE,
    UNKNOWN_IDENTITY,
    METADATA_MISMATCH,
    HISTORICAL_EVENT
  }

  private final Reason reason;

  public RejectedNotification(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
