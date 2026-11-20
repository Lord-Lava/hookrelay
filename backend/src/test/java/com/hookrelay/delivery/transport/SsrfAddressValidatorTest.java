package com.hookrelay.delivery.transport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.security.ssrf.SsrfValidationException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class SsrfAddressValidatorTest {

  @Test
  void acceptsPublicIPv4() throws Exception {
    SsrfAddressValidator.validate(InetAddress.getByName("8.8.8.8"));
    SsrfAddressValidator.validate(InetAddress.getByName("1.1.1.1"));
  }

  @Test
  void acceptsPublicIPv6() throws Exception {
    SsrfAddressValidator.validate(InetAddress.getByName("2001:4860:4860::8888"));
  }

  @Test
  void rejectsLoopbackIPv4() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("127.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsLoopbackIPv6() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("::1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsPrivateIPv4() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("10.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("172.16.0.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("192.168.1.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsLinkLocalIPv6() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("fe80::1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsDocumentationIPv6() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("2001:db8::1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsMulticastIPv4() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("224.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsZeroNetwork() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("0.0.0.0")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsCarrierGradeNAT() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("100.64.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsDocumentationIPv4() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("192.0.2.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("198.51.100.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("203.0.113.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsMappedIPv4InIPv6() throws Exception {
    assertThatThrownBy(
            () -> SsrfAddressValidator.validate(InetAddress.getByName("::ffff:10.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }
}
