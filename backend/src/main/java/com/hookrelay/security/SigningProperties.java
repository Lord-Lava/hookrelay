package com.hookrelay.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hookrelay.signing")
public record SigningProperties(
    String encryptionKey, String keyVersion, Map<String, String> decryptionKeys) {
  public SigningProperties {
    keyVersion = keyVersion == null ? "v1" : keyVersion;
    decryptionKeys = decryptionKeys == null ? Map.of() : Map.copyOf(decryptionKeys);
  }

  @Override
  public String toString() {
    return "SigningProperties[redacted]";
  }
}
