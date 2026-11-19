package com.hookrelay.delivery.domain;

public enum AttemptOutcome {
  STARTED,
  SUCCEEDED,
  RETRYABLE_FAILURE,
  PERMANENT_FAILURE,
  UNKNOWN,
  ABORTED;

  public void validateResult(Integer httpStatus, long durationMs) {
    if (this == STARTED
        || durationMs < 0
        || (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
        || (this == SUCCEEDED && (httpStatus == null || httpStatus < 200 || httpStatus > 299))
        || ((this == UNKNOWN || this == ABORTED) && httpStatus != null)) {
      throw new IllegalArgumentException("Invalid attempt result");
    }
  }
}
