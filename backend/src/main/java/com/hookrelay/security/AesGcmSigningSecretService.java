package com.hookrelay.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(SigningProperties.class)
public class AesGcmSigningSecretService implements SigningSecretService, InitializingBean {

  private static final String SECRET_PREFIX = "whsec_";
  private static final int SECRET_ENTROPY_BYTES = 32; // 256 bits
  private static final int GCM_NONCE_BITS = 96;
  private static final int GCM_TAG_BITS = 128;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final String encryptionKeyBase64;
  private final String keyVersion;
  private SecretKeySpec secretKey;
  private final Map<String, String> previousKeys;
  private Map<String, SecretKeySpec> decryptionKeys = Map.of();

  public AesGcmSigningSecretService(String key, String version) {
    this(new SigningProperties(key, version, Map.of()));
  }

  @Autowired
  public AesGcmSigningSecretService(SigningProperties properties) {
    this.encryptionKeyBase64 = properties.encryptionKey();
    this.keyVersion = properties.keyVersion();
    this.previousKeys = properties.decryptionKeys();
  }

  @Override
  public void afterPropertiesSet() {
    if (!keyVersion.matches("[A-Za-z0-9_-]{1,32}"))
      throw new IllegalStateException("Invalid signing key version");
    if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) return;
    secretKey = decodeKey(encryptionKeyBase64);
    Map<String, SecretKeySpec> keys = new HashMap<>();
    previousKeys.forEach((version, value) -> {
      if (!version.matches("[A-Za-z0-9_-]{1,32}") || version.equals(keyVersion))
        throw new IllegalStateException("Invalid or duplicate signing key version");
      keys.put(version, decodeKey(value));
    });
    keys.put(keyVersion, secretKey);
    decryptionKeys = Map.copyOf(keys);
  }

  private static SecretKeySpec decodeKey(String value) {
    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Signing encryption keys must be base64-encoded");
    }
    if (bytes.length != 32)
      throw new IllegalStateException("Signing encryption keys must decode to exactly 32 bytes");
    return new SecretKeySpec(bytes, "AES");
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
    if (nonce == null || nonce.length != 12) {
      throw new IllegalArgumentException("nonce must contain 12 bytes");
    }
    if (!decryptionKeys.containsKey(keyVersion)) {
      throw new IllegalStateException("Unsupported key version: " + keyVersion);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          decryptionKeys.get(keyVersion),
          new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to decrypt signing secret", exception);
    }
  }
}
