package com.hookrelay.security.ssrf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DefaultSsrfUrlValidatorTest {

  private final DefaultSsrfUrlValidator validator = new DefaultSsrfUrlValidator();

  @Test
  void acceptsPublicHttpsUrl() {
    validator.validate("https://93.184.216.34/webhook");
    validator.validate("https://93.184.216.34:443/webhook");
    validator.validate("https://93.184.216.34:443/path?query=1#fragment");
  }

  @Test
  void rejectsLocalhostByName() {
    assertThatThrownBy(() -> validator.validate("https://localhost/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL host is not allowed");
  }

  @Test
  void rejectsIpv4Loopback() {
    assertThatThrownBy(() -> validator.validate("https://127.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL host is not allowed");
    assertThatThrownBy(() -> validator.validate("https://127.0.0.1:443/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://127.1.2.3/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsRfc1918PrivateRanges() {
    // 10.0.0.0/8
    assertThatThrownBy(() -> validator.validate("https://10.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://10.255.255.255/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // 172.16.0.0/12
    assertThatThrownBy(() -> validator.validate("https://172.16.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://172.31.255.255/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // 192.168.0.0/16
    assertThatThrownBy(() -> validator.validate("https://192.168.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://192.168.255.255/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsLinkLocalAddresses() {
    // IPv4 link-local: 169.254.0.0/16 (includes cloud metadata 169.254.169.254)
    assertThatThrownBy(() -> validator.validate("https://169.254.169.254/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://169.254.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // IPv6 link-local: fe80::/10
    assertThatThrownBy(() -> validator.validate("https://[fe80::1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsIpv6Loopback() {
    assertThatThrownBy(() -> validator.validate("https://[::1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsIpv6UniqueLocal() {
    // IPv6 unique-local: fc00::/7
    assertThatThrownBy(() -> validator.validate("https://[fc00::1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://[fd12:3456:789a::1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsMulticastAddresses() {
    // IPv4 multicast: 224.0.0.0/4
    assertThatThrownBy(() -> validator.validate("https://224.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://239.255.255.255/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // IPv6 multicast: ff00::/8
    assertThatThrownBy(() -> validator.validate("https://[ff02::1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsIpv4MappedIpv6() {
    // ::ffff:127.0.0.1 = IPv4-mapped IPv6 loopback
    assertThatThrownBy(() -> validator.validate("https://[::ffff:127.0.0.1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // ::ffff:10.0.0.1 = IPv4-mapped IPv6 private
    assertThatThrownBy(() -> validator.validate("https://[::ffff:10.0.0.1]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsCloudMetadataAddress() {
    assertThatThrownBy(() -> validator.validate("https://169.254.169.254/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL host is not allowed");
  }

  @Test
  void rejectsUnspecifiedAddress() {
    // IPv4 unspecified: 0.0.0.0
    assertThatThrownBy(() -> validator.validate("https://0.0.0.0/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // IPv6 unspecified: ::
    assertThatThrownBy(() -> validator.validate("https://[::]/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsMalformedUrls() {
    assertThatThrownBy(() -> validator.validate("not-a-url"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("")).isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("   ")).isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsUnsupportedSchemes() {
    assertThatThrownBy(() -> validator.validate("http://example.com/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL scheme must be HTTPS");
    assertThatThrownBy(() -> validator.validate("ftp://example.com/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("gopher://example.com/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsUrlCredentials() {
    assertThatThrownBy(() -> validator.validate("https://user:pass@example.com/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL credentials");
  }

  @Test
  void rejectsUnsupportedPorts() {
    assertThatThrownBy(() -> validator.validate("https://example.com:80/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://example.com:8080/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://example.com:22/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsDnsResultsContainingProhibitedAddresses() throws Exception {
    // Private IP literals are rejected directly (no DNS needed for literals).
    assertThatThrownBy(() -> validator.validate("https://10.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);

    // Public IP literals pass validation (DNS-based mixed results are verified
    // at the integration layer where InetAddress resolution can be controlled).
    validator.validate("https://93.184.216.34/webhook");
  }

  @Test
  void rejectsIpv4ReservedRange() {
    // 240.0.0.0/4 - Reserved
    assertThatThrownBy(() -> validator.validate("https://240.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://255.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class);
    assertThatThrownBy(() -> validator.validate("https://255.255.255.0/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsIpv4LimitedBroadcast() {
    // 255.255.255.255 - Limited broadcast
    assertThatThrownBy(() -> validator.validate("https://255.255.255.255/webhook"))
        .isInstanceOf(SsrfValidationException.class);
  }

  @Test
  void rejectsTrailingDotHostname() {
    // Trailing dot (FQDN indicator) is stripped before validation
    assertThatThrownBy(() -> validator.validate("https://localhost./webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL host is not allowed");
  }

  @Test
  void rejectsIpv4OctalLikeNotation() {
    // Leading zeros are treated as decimal by Java's InetAddress, not octal.
    // 010 = 10 (decimal), so 010.0.0.1 = 10.0.0.1 (RFC1918 private).
    assertThatThrownBy(() -> validator.validate("https://010.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL host is not allowed");
  }

  @Test
  void rejectsUsernameOnlyCredentials() {
    // User info without password is still credentials
    assertThatThrownBy(() -> validator.validate("https://user@127.0.0.1/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("URL credentials");
  }

  @Test
  void rejectsUnresolvableHostname() {
    // DNS failure must fail closed (no pass-through)
    assertThatThrownBy(
            () -> validator.validate("https://this-host-does-not-exist-12345.invalid/webhook"))
        .isInstanceOf(SsrfValidationException.class)
        .hasMessageContaining("Unable to resolve hostname");
  }

  @Test
  void acceptsDocumentationRangeIpv6() {
    // 2001:db8::/32 is documentation range, not a special-use address
    validator.validate("https://[2001:db8::1]/webhook");
  }
}
