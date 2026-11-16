package com.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;

class AesGcmSigningSecretServiceTest {

  private static final String TEST_KEY_BASE64 =
      Base64.getEncoder()
          .encodeToString(
              new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
              });

  private SigningSecretService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new AesGcmSigningSecretService(TEST_KEY_BASE64, "v1");
    ((InitializingBean) service).afterPropertiesSet();
  }

  private SigningSecretService newService(String keyBase64) {
    return new AesGcmSigningSecretService(keyBase64, "v1");
  }

  @Test
  void generateSecretHasWhsecPrefix() {
    String secret = service.generateSecret();
    assertThat(secret).startsWith("whsec_");
  }

  @Test
  void generateSecretHas256BitsOfEntropy() {
    String secret = service.generateSecret();
    String entropyPart = secret.substring("whsec_".length());
    assertThat(entropyPart).hasSize(43);
    byte[] decoded = Base64.getUrlDecoder().decode(entropyPart);
    assertThat(decoded).hasSize(32);
  }

  @Test
  void generateSecretProducesUniqueValues() {
    String secret1 = service.generateSecret();
    String secret2 = service.generateSecret();
    assertThat(secret1).isNotEqualTo(secret2);
  }

  @Test
  void encryptDecryptRoundTrip() {
    String plaintext = "whsec_test_secret_plaintext_value";
    SigningSecretService.EncryptedSecret encrypted = service.encrypt(plaintext);

    assertThat(encrypted.ciphertext()).isNotBlank();
    assertThat(encrypted.nonce()).isNotNull();
    assertThat(encrypted.nonce()).hasSize(12);
    assertThat(encrypted.keyVersion()).isEqualTo("v1");

    String decrypted = service.decrypt(encrypted.ciphertext(), encrypted.nonce(), "v1");
    assertThat(decrypted).isEqualTo(plaintext);
  }

  @Test
  void encryptProducesUniqueNonces() {
    String plaintext = "whsec_same_plaintext_twice";
    SigningSecretService.EncryptedSecret e1 = service.encrypt(plaintext);
    SigningSecretService.EncryptedSecret e2 = service.encrypt(plaintext);

    assertThat(e1.nonce()).isNotEqualTo(e2.nonce());
    assertThat(e1.ciphertext()).isNotEqualTo(e2.ciphertext());
  }

  @Test
  void ciphertextTamperingCausesDecryptionFailure() {
    String plaintext = "whsec_tamper_me";
    SigningSecretService.EncryptedSecret encrypted = service.encrypt(plaintext);

    byte[] ciphertextBytes = Base64.getDecoder().decode(encrypted.ciphertext());
    ciphertextBytes[0] ^= 0x01;
    String tamperedCiphertext = Base64.getEncoder().encodeToString(ciphertextBytes);

    assertThatThrownBy(() -> service.decrypt(tamperedCiphertext, encrypted.nonce(), "v1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nonceTamperingCausesDecryptionFailure() {
    String plaintext = "whsec_tamper_nonce";
    SigningSecretService.EncryptedSecret encrypted = service.encrypt(plaintext);

    byte[] tamperedNonce = encrypted.nonce().clone();
    tamperedNonce[0] ^= 0x01;

    assertThatThrownBy(() -> service.decrypt(encrypted.ciphertext(), tamperedNonce, "v1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wrongKeyFailsDecryption() throws Exception {
    String plaintext = "whsec_wrong_key_test";
    SigningSecretService.EncryptedSecret encrypted = service.encrypt(plaintext);

    byte[] differentKey = new byte[32];
    for (int i = 0; i < 32; i++) {
      differentKey[i] = (byte) (0xFF);
    }
    String differentKeyBase64 = Base64.getEncoder().encodeToString(differentKey);
    SigningSecretService otherService = newService(differentKeyBase64);
    ((InitializingBean) otherService).afterPropertiesSet();

    assertThatThrownBy(() -> otherService.decrypt(encrypted.ciphertext(), encrypted.nonce(), "v1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void unsupportedKeyVersionFails() {
    String plaintext = "whsec_version_test";
    SigningSecretService.EncryptedSecret encrypted = service.encrypt(plaintext);

    assertThatThrownBy(() -> service.decrypt(encrypted.ciphertext(), encrypted.nonce(), "v2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported key version");
  }

  @Test
  void missingEncryptionKeyServiceIsNoOp() throws Exception {
    SigningSecretService service = new AesGcmSigningSecretService("", "v1");
    ((InitializingBean) service).afterPropertiesSet();
    // Service initializes but reports not configured
    assertThat(service.isConfigured()).isFalse();
    // Cannot encrypt/decrypt without a valid key
    assertThatThrownBy(() -> service.encrypt("whsec_test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> service.decrypt("whsec_ciphertext", new byte[12], "v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");
  }

  @Test
  void invalidBase64KeyFailsInitialization() {
    SigningSecretService service = new AesGcmSigningSecretService("not-valid-base64!!!", "v1");
    assertThatThrownBy(InitializingBean.class.cast(service)::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base64-encoded");
  }

  @Test
  void wrongKeySizeFailsInitialization() {
    byte[] shortKey = new byte[16];
    String shortKeyBase64 = Base64.getEncoder().encodeToString(shortKey);
    SigningSecretService service = new AesGcmSigningSecretService(shortKeyBase64, "v1");
    assertThatThrownBy(InitializingBean.class.cast(service)::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly 32 bytes");
  }

  @Test
  void encryptRejectsBlankPlaintext() {
    assertThatThrownBy(() -> service.encrypt("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.encrypt("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decryptRejectsBlankCiphertext() {
    assertThatThrownBy(() -> service.decrypt("", new byte[12], "v1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decryptRejectsEmptyNonce() {
    assertThatThrownBy(() -> service.decrypt("whsec_ciphertext", new byte[0], "v1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
