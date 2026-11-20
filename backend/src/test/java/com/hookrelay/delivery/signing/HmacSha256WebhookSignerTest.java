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
  void givenSecretWithWhsecPrefix_whenSigning_thenProducesValidV1Signature() {
    String signature = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(signature).startsWith("v1,");
    String signatureBase64 = signature.substring("v1,".length());
    byte[] decoded = Base64.getDecoder().decode(signatureBase64);
    assertThat(decoded).hasSize(32); // HMAC-SHA256 produces 32-byte digest
  }

  @Test
  void givenSecretWithoutWhsecPrefix_whenSigning_thenProducesValidV1Signature() {
    String secretWithoutPrefix = TEST_SECRET.substring("whsec_".length());
    String signature = signer.sign(deliveryId, timestamp, body, secretWithoutPrefix);

    assertThat(signature).startsWith("v1,");
    String signatureBase64 = signature.substring("v1,".length());
    byte[] decoded = Base64.getDecoder().decode(signatureBase64);
    assertThat(decoded).hasSize(32);
  }

  @Test
  void givenSameInputs_whenSigningTwice_thenProducesSameSignature() {
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(sig1).isEqualTo(sig2);
  }

  @Test
  void givenDifferentDeliveryIds_whenSigningSameBody_thenProducesDifferentSignatures() {
    UUID otherId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(otherId, timestamp, body, TEST_SECRET);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void givenDifferentTimestamps_whenSigningSameDelivery_thenProducesDifferentSignatures() {
    long otherTime = timestamp + 3600;
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, otherTime, body, TEST_SECRET);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void givenDifferentBodies_whenSigningSameDelivery_thenProducesDifferentSignatures() {
    byte[] otherBody =
        "{\"eventType\":\"test.other\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, otherBody, TEST_SECRET);

    assertThat(sig1).isNotEqualTo(sig2);
  }

  @Test
  void givenDifferentSecrets_whenSigningSameDelivery_thenProducesDifferentSignatures() {
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
  void givenNullDeliveryId_whenSigning_thenThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> signer.sign(null, timestamp, body, TEST_SECRET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deliveryId");
  }

  @Test
  void givenNullBody_whenSigning_thenThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, null, TEST_SECRET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bodyBytes");
  }

  @Test
  void givenNullSecret_whenSigning_thenThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, body, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secretPlaintext");
  }

  @Test
  void givenBlankSecret_whenSigning_thenThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, body, "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secretPlaintext");
  }

  @Test
  void givenInvalidBase64Secret_whenSigning_thenThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> signer.sign(deliveryId, timestamp, body, "whsec_!!!not-base64!!!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base64url");
  }

  @Test
  void givenValidSignature_whenInspected_thenMatchesV1Base64Format() {
    String signature = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(signature).matches("^v1,[A-Za-z0-9+/=]+$");
  }

  @Test
  void givenSameInputs_whenSigningThreeTimes_thenProducesIdenticalSignatures() {
    String sig1 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig2 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);
    String sig3 = signer.sign(deliveryId, timestamp, body, TEST_SECRET);

    assertThat(sig1).isEqualTo(sig2);
    assertThat(sig2).isEqualTo(sig3);
  }
}
