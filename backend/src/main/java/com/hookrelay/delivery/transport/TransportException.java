package com.hookrelay.delivery.transport;

/**
 * Thrown when a webhook transport request cannot be sent safely or fails at the transport level.
 * Does not include HTTP response bodies or other potentially sensitive payload data.
 */
public class TransportException extends Exception {

  @java.io.Serial private static final long serialVersionUID = 1L;

  public TransportException(String message) {
    super(message);
  }

  public TransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
