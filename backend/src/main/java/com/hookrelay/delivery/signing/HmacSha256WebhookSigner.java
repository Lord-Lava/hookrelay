package com.hookrelay.delivery.signing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 implementation of {@link WebhookSigner}.
 *
 * <p>The signed message is: {@code webhook-id.webhook-timestamp.rawBodyBytes} The signature is
 * formatted as: {@code v1,<base64(HMAC-SHA256(key, message))>}
 *
 * <p>The signing key is derived from the webhook signing secret by stripping the {@code whsec_}
 * prefix (if present) and base64url-decoding the remainder. The resulting key must be 32 bytes (256
 * bits).
 */
public class HmacSha256WebhookSigner implements WebhookSigner {

  private static final String SIGNATURE_PREFIX = "v1,";
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String SECRET_PREFIX = "whsec_";

  @Override
  public String sign(
      UUID deliveryId, long timestampSeconds, byte[] bodyBytes, String secretPlaintext) {
    if (deliveryId == null) {
      throw new IllegalArgumentException("deliveryId must not be null");
    }
    if (bodyBytes == null) {
      throw new IllegalArgumentException("bodyBytes must not be null");
    }
    if (secretPlaintext == null || secretPlaintext.isBlank()) {
      throw new IllegalArgumentException("secretPlaintext must not be blank");
    }

    // Strip the whsec_ prefix if present to get the raw base64url-encoded key
    String keyMaterial = secretPlaintext;
    if (keyMaterial.startsWith(SECRET_PREFIX)) {
      keyMaterial = keyMaterial.substring(SECRET_PREFIX.length());
    }

    byte[] key;
    try {
      key = Base64.getUrlDecoder().decode(keyMaterial);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Signing secret must be base64url-encoded", e);
    }

    Mac mac;
    try {
      mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Failed to initialize HMAC-SHA256", e);
    }

    String message =
        deliveryId.toString()
            + "."
            + timestampSeconds
            + "."
            + new String(bodyBytes, StandardCharsets.UTF_8);
    byte[] signature = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

    return SIGNATURE_PREFIX + Base64.getEncoder().encodeToString(signature);
  }
}
