package com.hookrelay.delivery.transport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookrelay.security.ssrf.SsrfValidationException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class SsrfAddressValidatorTest {

  @Test
  void givenPublicIPv4Address_whenValidated_thenAccepted() throws Exception {
    SsrfAddressValidator.validate(InetAddress.getByName("8.8.8.8"));
    SsrfAddressValidator.validate(InetAddress.getByName("1.1.1.1"));
  }

  @Test
  void givenPublicIPv6Address_whenValidated_thenAccepted() throws Exception {
    SsrfAddressValidator.validate(InetAddress.getByName("2001:4860:4860::8888"));
  }

  @Test
  void givenLoopbackIPv4Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("127.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenLoopbackIPv6Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("::1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenPrivateIPv4Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("10.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("172.16.0.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("192.168.1.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenLinkLocalIPv6Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("fe80::1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenDocumentationIPv6Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("2001:db8::1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenMulticastIPv4Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("224.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenZeroNetworkAddress_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("0.0.0.0")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenCarrierGradeNATAddress_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("100.64.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenDocumentationIPv4Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("192.0.2.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("198.51.100.1")))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> SsrfAddressValidator.validate(InetAddress.getByName("203.0.113.1")))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void givenMappedIPv4InIPv6Address_whenValidated_thenRejected() throws Exception {
    assertThatThrownBy(
            () -> SsrfAddressValidator.validate(InetAddress.getByName("::ffff:10.0.0.1")))
        .isInstanceOf(SsrfValidationException.class);
  }
}
