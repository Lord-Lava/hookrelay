package com.hookrelay.delivery.transport;

/**
 * Sends webhook HTTP POST requests with SSRF-safe connection handling.
 *
 * <p>Implementations must validate DNS at connection time, pin connections to validated addresses
 * only, and never connect to unvalidated destinations. This interface is intentionally minimal so
 * that HTTP behaviour can be tested without weakening production SSRF policy.
 */
public interface WebhookTransport {

  /**
   * Send a POST request to the given URL with the provided body.
   *
   * @param url the destination URL (HTTPS only, port 443)
   * @param body the request body bytes
   * @return the transport result with status code and bounded body preview
   * @throws TransportException if the request cannot be sent safely or fails
   */
  TransportResult send(String url, byte[] body) throws TransportException;
}
