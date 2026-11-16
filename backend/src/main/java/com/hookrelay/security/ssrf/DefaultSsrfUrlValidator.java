package com.hookrelay.security.ssrf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link SsrfUrlValidator} using only the JDK.
 *
 * <p>Performs registration-time SSRF validation:
 *
 * <ul>
 *   <li>Only the HTTPS scheme is permitted
 *   <li>URL credentials (user:pass@host) are rejected
 *   <li>Only port 443 (or no explicit port) is permitted
 *   <li>Loopback, private (RFC1918), link-local, multicast, unspecified, and reserved addresses are
 *       rejected for both IPv4 and IPv6
 *   <li>IPv4-mapped IPv6 addresses (::ffff:w.x.y.z) are rejected
 *   <li>Hostnames are resolved via DNS and <b>every</b> resolved address is checked
 * </ul>
 *
 * <p><b>Important limitation:</b> This is registration-time validation only. DNS rebinding (a
 * hostname that resolves to a public address at registration time but a private address at
 * connection time) cannot be prevented here. Connection-time enforcement must be implemented in the
 * Phase 3 delivery client.
 */
@Service
public class DefaultSsrfUrlValidator implements SsrfUrlValidator {

  private static final String ALLOWED_SCHEME = "https";
  private static final int DEFAULT_HTTPS_PORT = 443;

  @Override
  public void validate(String url) {
    UrlParts parts = parseUrl(url);
    validateScheme(parts);
    validateNoUserInfo(parts);
    validatePort(parts);
    validateHost(parts);
  }

  private UrlParts parseUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new SsrfValidationException("URL must not be blank");
    }

    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException exception) {
      throw new SsrfValidationException("Malformed URL: " + url, exception);
    }

    return new UrlParts(
        uri.getScheme(),
        uri.getHost(),
        uri.getPort(), // -1 when no explicit port
        uri.getUserInfo(),
        url);
  }

  private void validateScheme(UrlParts parts) {
    if (parts.scheme() == null || !ALLOWED_SCHEME.equalsIgnoreCase(parts.scheme())) {
      throw new SsrfValidationException("URL scheme must be HTTPS, got: " + parts.scheme());
    }
  }

  private void validateNoUserInfo(UrlParts parts) {
    if (parts.userInfo() != null && !parts.userInfo().isBlank()) {
      throw new SsrfValidationException("URL credentials (user:password) are not allowed");
    }
  }

  private void validatePort(UrlParts parts) {
    int port = parts.port();
    if (port == -1) {
      return; // default HTTPS port 443
    }
    if (port < 0 || port > 65535) {
      throw new SsrfValidationException("Invalid port: " + port);
    }
    if (port != DEFAULT_HTTPS_PORT) {
      throw new SsrfValidationException(
          "Only port " + DEFAULT_HTTPS_PORT + " is allowed for HTTPS URLs, got: " + port);
    }
  }

  private void validateHost(UrlParts parts) {
    String host = parts.host();
    if (host == null || host.isBlank()) {
      throw new SsrfValidationException("URL host is required");
    }

    // Normalize: trim, lowercase, strip trailing dot (FQDN indicator)
    String normalized = host.trim().toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isBlank()) {
      throw new SsrfValidationException("URL host is required");
    }

    // Try parsing as a literal IP address first (no DNS lookup for literals)
    try {
      InetAddress literal = InetAddress.getByName(normalized);
      validateResolvedAddress(literal, normalized);
      return;
    } catch (UnknownHostException expected) {
      // Not a literal IP address; treat as a hostname and resolve via DNS
    }

    // Hostname: resolve via DNS and validate every resolved address
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(normalized);
    } catch (UnknownHostException exception) {
      // DNS resolution failure: fail closed. A hostname that cannot be resolved
      // cannot be validated and must be rejected. Connection-time enforcement in
      // Phase 3 is the authoritative SSRF defense, but registration-time validation
      // must not allow unverifiable hostnames through.
      throw new SsrfValidationException("Unable to resolve hostname: " + normalized, exception);
    }

    if (addresses == null || addresses.length == 0) {
      throw new SsrfValidationException("Unable to resolve hostname: " + normalized);
    }

    for (InetAddress address : addresses) {
      validateResolvedAddress(address, normalized);
    }
  }

  private void validateResolvedAddress(InetAddress address, String host) {
    // Reject IPv4-mapped IPv6 addresses (::ffff:w.x.y.z)
    if (address instanceof Inet6Address && isIPv4MappedIPv6(address)) {
      throw new SsrfValidationException(
          "IPv4-mapped IPv6 addresses are not allowed: "
              + host
              + " ("
              + address.getHostAddress()
              + ")");
    }

    // Reject IPv6 unique-local addresses (fc00::/7). Java's isSiteLocalAddress() covers
    // fec0::/10 (site-local) but not fc00::/7 (unique-local), so check explicitly.
    if (address instanceof Inet6Address && isIPv6UniqueLocal(address)) {
      throw new SsrfValidationException(
          "IPv6 unique-local addresses are not allowed: "
              + host
              + " ("
              + address.getHostAddress()
              + ")");
    }

    // Reject IPv4 reserved ranges (240.0.0.0/4 including 255.255.255.255 limited broadcast)
    // These are not covered by Java's isSiteLocalAddress()/isMulticastAddress() methods.
    if (address instanceof Inet4Address && isIPv4Reserved(address)) {
      throw new SsrfValidationException(
          "IPv4 reserved address is not allowed: " + host + " (" + address.getHostAddress() + ")");
    }

    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()
        || address.isSiteLocalAddress()) {
      throw new SsrfValidationException(
          "URL host is not allowed: " + host + " (" + address.getHostAddress() + ")");
    }
  }

  /**
   * Detects IPv4-mapped IPv6 addresses (::ffff:w.x.y.z). The wire encoding is 10 zero bytes,
   * followed by 0xff 0xff, followed by the 4-byte IPv4 address.
   */
  private static boolean isIPv4MappedIPv6(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != 16) {
      return false;
    }
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }

  /**
   * Detects IPv6 unique-local addresses (fc00::/7). The first 7 bits are 1111110, so the first byte
   * masked with 0xfe equals 0xfc.
   */
  private static boolean isIPv6UniqueLocal(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != 16) {
      return false;
    }
    return (bytes[0] & 0xfe) == 0xfc;
  }

  /**
   * Detects IPv4 reserved addresses (240.0.0.0/4). This range includes 255.255.255.255 (limited
   * broadcast) and is not covered by Java's isSiteLocalAddress()/isMulticastAddress() methods.
   */
  private static boolean isIPv4Reserved(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != 4) {
      return false;
    }
    return (bytes[0] & 0xF0) == 0xF0;
  }

  /** Parsed components of a URL relevant to SSRF validation. */
  record UrlParts(String scheme, String host, int port, String userInfo, String originalUrl) {}
}
