package com.hookrelay.delivery.signing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
  private static final byte[] DOT = ".".getBytes(StandardCharsets.UTF_8);
  private static final int KEY_SIZE_BYTES = 32;

  private final ConcurrentHashMap<String, SecretKeySpec> keyCache = new ConcurrentHashMap<>();
  private final ThreadLocal<Mac> macThreadLocal =
      ThreadLocal.withInitial(() -> {
        try {
          return Mac.getInstance(HMAC_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
          throw new ExceptionInInitializerError(e);
        }
      });

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

    SecretKeySpec cachedKey = keyCache.computeIfAbsent(secretPlaintext, this::decodeKey);

    Mac mac = macThreadLocal.get();
    mac.reset();
    try {
      mac.init(cachedKey);
    } catch (InvalidKeyException e) {
      throw new IllegalStateException("Failed to initialize HMAC-SHA256", e);
    }

    byte[] deliveryIdBytes = deliveryId.toString().getBytes(StandardCharsets.UTF_8);
    byte[] timestampBytes = Long.toString(timestampSeconds).getBytes(StandardCharsets.UTF_8);

    mac.update(deliveryIdBytes);
    mac.update(DOT);
    mac.update(timestampBytes);
    mac.update(DOT);
    mac.update(bodyBytes);
    byte[] signature = mac.doFinal();

    return SIGNATURE_PREFIX + Base64.getEncoder().encodeToString(signature);
  }

  private SecretKeySpec decodeKey(String secretPlaintext) {
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
    if (key.length != KEY_SIZE_BYTES) {
      throw new IllegalArgumentException("Signing key must decode to exactly 32 bytes");
    }
    return new SecretKeySpec(key, HMAC_ALGORITHM);
  }
}
