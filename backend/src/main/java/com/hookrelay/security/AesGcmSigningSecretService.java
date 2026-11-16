package com.hookrelay.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM implementation of {@link SigningSecretService}.
 *
 * <p>The encryption key is injected through {@code hookrelay.signing.encryption-key}
 * (base64-encoded 256-bit key) and must be provided via external configuration. No default or
 * hardcoded key is ever used. When no key is configured, {@link #isConfigured()} returns false and
 * encrypt/decrypt throw {@link IllegalStateException}.
 */
@Service
public class AesGcmSigningSecretService implements SigningSecretService, InitializingBean {

  private static final String SECRET_PREFIX = "whsec_";
  private static final int SECRET_ENTROPY_BYTES = 32; // 256 bits
  private static final int GCM_NONCE_BITS = 96;
  private static final int GCM_TAG_BITS = 128;
  private static final String DEFAULT_KEY_VERSION = "v1";

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final String encryptionKeyBase64;
  private final String keyVersion;
  private SecretKeySpec secretKey;

  public AesGcmSigningSecretService(
      @Value("${hookrelay.signing.encryption-key:#{null}}") String encryptionKeyBase64,
      @Value("${hookrelay.signing.key-version:v1}") String keyVersion) {
    this.encryptionKeyBase64 = encryptionKeyBase64;
    this.keyVersion = keyVersion;
  }

  @Override
  public void afterPropertiesSet() {
    if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
      return; // No encryption key configured - encrypt/decrypt will throw
    }
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "hookrelay.signing.encryption-key must be base64-encoded", exception);
    }
    if (keyBytes.length != SECRET_ENTROPY_BYTES) {
      throw new IllegalStateException(
          "hookrelay.signing.encryption-key must decode to exactly "
              + SECRET_ENTROPY_BYTES
              + " bytes (256 bits), got "
              + keyBytes.length);
    }
    this.secretKey = new SecretKeySpec(keyBytes, "AES");
  }

  @Override
  public boolean isConfigured() {
    return secretKey != null;
  }

  @Override
  public String generateSecret() {
    byte[] entropy = new byte[SECRET_ENTROPY_BYTES];
    SECURE_RANDOM.nextBytes(entropy);
    return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
  }

  @Override
  public EncryptedSecret encrypt(String plaintext) {
    if (!isConfigured()) {
      throw new IllegalStateException("Signing secret encryption is not configured");
    }
    if (plaintext == null || plaintext.isBlank()) {
      throw new IllegalArgumentException("plaintext must not be blank");
    }
    byte[] nonce = new byte[GCM_NONCE_BITS / 8];
    SECURE_RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return new EncryptedSecret(Base64.getEncoder().encodeToString(ciphertext), nonce, keyVersion);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to encrypt signing secret", exception);
    }
  }

  @Override
  public String decrypt(String ciphertext, byte[] nonce, String keyVersion) {
    if (!isConfigured()) {
      throw new IllegalStateException("Signing secret encryption is not configured");
    }
    if (ciphertext == null || ciphertext.isBlank()) {
      throw new IllegalArgumentException("ciphertext must not be blank");
    }
    if (nonce == null || nonce.length == 0) {
      throw new IllegalArgumentException("nonce must not be empty");
    }
    if (!this.keyVersion.equals(keyVersion)) {
      throw new IllegalStateException("Unsupported key version: " + keyVersion);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to decrypt signing secret", exception);
    }
  }
}
