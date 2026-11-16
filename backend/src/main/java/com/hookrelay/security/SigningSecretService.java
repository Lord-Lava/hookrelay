package com.hookrelay.security;

public interface SigningSecretService {

  /**
   * Generates a new signing secret with the {@code whsec_} prefix and at least 256 bits of
   * cryptographically secure randomness.
   */
  String generateSecret();

  /** Encrypts a plaintext signing secret using AES-256-GCM with a unique nonce. */
  EncryptedSecret encrypt(String plaintext);

  /** Decrypts an encrypted signing secret back to plaintext. */
  String decrypt(String ciphertext, byte[] nonce, String keyVersion);

  /** Returns whether the service is configured with a valid encryption key. */
  boolean isConfigured();

  /** Holds the result of an encryption operation. */
  record EncryptedSecret(String ciphertext, byte[] nonce, String keyVersion) {}
}
