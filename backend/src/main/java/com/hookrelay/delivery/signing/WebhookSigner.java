package com.hookrelay.delivery.signing;

import java.util.UUID;

/**
 * Signs webhook payloads using HMAC-SHA256 per the Standard Webhooks v1 specification.
 *
 * <p>The signed message is: {@code webhook-id.webhook-timestamp.rawBodyBytes} The signature is
 * formatted as: {@code v1,<base64(HMAC-SHA256(key, message))>}
 *
 * <p>The signing key is derived from the webhook signing secret by stripping the {@code whsec_}
 * prefix (if present) and base64url-decoding the remainder. The resulting key must be 32 bytes (256
 * bits).
 */
public interface WebhookSigner {

  /**
   * Signs a webhook payload.
   *
   * @param deliveryId the stable delivery UUID
   * @param timestampSeconds Unix epoch seconds
   * @param bodyBytes the raw body bytes
   * @param secretPlaintext the plaintext signing secret (with or without "whsec_" prefix)
   * @return the signature string in format "v1,<base64>"
   * @throws IllegalArgumentException if any argument is invalid
   */
  String sign(UUID deliveryId, long timestampSeconds, byte[] bodyBytes, String secretPlaintext);
}
