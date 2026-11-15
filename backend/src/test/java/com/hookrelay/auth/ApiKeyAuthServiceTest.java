package com.hookrelay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyAuthServiceTest {
  @Test
  void sha256IsStableAndHexEncoded() {
    String hash = ApiKeyAuthService.sha256("hr_live_localdev_development_only_change_me");
    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(hash)
        .isEqualTo(ApiKeyAuthService.sha256("hr_live_localdev_development_only_change_me"));
    assertThat(hash)
        .isNotEqualTo(ApiKeyAuthService.sha256("hr_live_localdev_different_secret_value"));
  }
}
