package com.hookrelay.security.ssrf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class DnsPolicyTest {
  @Test
  void rejectsMixedAnswersRegardlessOfOrder() throws Exception {
    InetAddress publicIp = InetAddress.ofLiteral("8.8.8.8");
    InetAddress privateIp = InetAddress.ofLiteral("10.0.0.1");
    for (InetAddress[] answers :
        new InetAddress[][] {{publicIp, privateIp}, {privateIp, publicIp}}) {
      var validator = new DefaultSsrfUrlValidator(host -> answers);
      assertThatThrownBy(() -> validator.validate("https://customer.example/hook"))
          .isInstanceOf(SsrfValidationException.class);
    }
  }

  @Test
  void rejectsSpecialUseAndMappedAddresses() {
    var validator = new DefaultSsrfUrlValidator();
    for (String host :
        new String[] {
          "0.1.2.3",
          "100.64.0.1",
          "192.0.2.1",
          "198.18.0.1",
          "198.51.100.1",
          "203.0.113.1",
          "[2001:db8::1]",
          "[::ffff:8.8.8.8]",
          "[64:ff9b::a00:1]"
        }) {
      assertThatThrownBy(() -> validator.validate("https://" + host + "/hook"))
          .as(host)
          .isInstanceOf(SsrfValidationException.class);
    }
  }

  @Test
  void errorsDoNotDiscloseResolvedAddressesOrCredentials() throws Exception {
    var validator =
        new DefaultSsrfUrlValidator(host -> new InetAddress[] {InetAddress.ofLiteral("10.2.3.4")});
    assertThatThrownBy(() -> validator.validate("https://customer.example/hook"))
        .hasMessageNotContaining("10.2.3.4");
    assertThatThrownBy(
            () -> validator.validate("https://user:password@customer.example/invalid path"))
        .hasMessageNotContaining("password");
  }
}
