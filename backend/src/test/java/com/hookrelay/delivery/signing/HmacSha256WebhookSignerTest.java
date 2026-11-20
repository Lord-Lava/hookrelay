package com.hookrelay.delivery.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HmacSha256WebhookSignerTest {

  private static final String TEST_SECRET = "whsec_" + base64Url(key32Bytes());

  private static byte[] key32Bytes() {
    byte[] bytes = new byte[32];
    for (int i = 0; i < 32; i++) {
      bytes[i] = (byte) (i + 1);
    }
    return bytes;
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private final WebhookSigner signer = new HmacSha256WebhookSigner();

  private final UUID deliveryId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private final long timestamp = 1735689600L; // 2024-12-31T00:00:00Z
  private final byte[] body =
      "{\"eventType\":\"test.created\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);

  @Test
  void signWithWhsecPrefixProducesValidSignature() {
    String signature = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(signature).startsWith("v1,");
    String signatureBase64 = signature.substring("v1,".length());
    byte[] decoded = Base64.getDecoder().decode(signatureBase64);
    assertThat(decoded).hasSize(32); // HMAC-SHA256 produces 32-byte digest
  }

  @Test
  void signWithoutWhsecPrefixProducesValidSignature() {
    String secretWithoutPrefix = TEST_SECRET.substring("whsec_".length());
    String signature = signer.sign(deliveryId, timestamp, body, secretWithoutPrefix);

    assertThat(signature).startsWith("v1,");
    String signatureBase64 = signature.substring("v1,".length());
    byte[] decoded = Base64.getDecoder().decode(signatureBase64);
    assertThat(decoded).hasSize(32);
  }

  @Test
  void sameInputProducesSameSignature() {
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(sig1).isEqualTo(sig2);
  }

  @Test
  void differentDeliveryIdProducesDifferentSignature() {
    UUID otherId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(otherId, timestamp, body, TEST_SECRET);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void differentTimestampProducesDifferentSignature() {
    long otherTime = timestamp + 3600;
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, otherTime, body, TEST_SECRET);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void differentBodyProducesDifferentSignature() {
    byte[] otherBody =
        "{\"eventType\":\"test.other\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, otherBody, TEST_SECRET);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void differentSecretProducesDifferentSignature() {
    byte[] otherKeyBytes = new byte[32];
    for (int i = 0; i < 32; i++) {
      otherKeyBytes[i] = (byte) (255 - i);
    }
    String otherSecret = "whsec_" + base64Url(otherKeyBytes);
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, body, otherSecret);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void signWithNullDeliveryIdThrows() {
    assertThatThrownBy(() -> signer.sign(null, timestamp, body, TEST_SECRET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deliveryId");
  }

  @Test
  void signWithNullBodyThrows() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, null, TEST_SECRET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bodyBytes");
  }

  @Test
  void signWithNullSecretThrows() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, body, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secretPlaintext");
  }

  @Test
  void signWithBlankSecretThrows() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, body, "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secretPlaintext");
  }

  @Test
  void signWithInvalidBase64SecretThrows() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, body, "whsec_!!!not-base64!!!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base64url");
  }

  @Test
  void signatureFormatIsCorrect() {
    String signature = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(signature).matches("^v1,[A-Za-z0-9+/=]+$");
  }

  @Test
  void signatureIsDeterministicAcrossCalls() {
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig3 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(sig1).isEqualTo(sig2);
    assertThat(sig2).isEqualTo(sig3);
  }
}
