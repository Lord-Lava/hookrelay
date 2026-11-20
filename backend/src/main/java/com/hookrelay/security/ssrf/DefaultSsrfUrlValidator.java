package com.hookrelay.security.ssrf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Registration policy only. Delivery must pin validated addresses at connection time. */
@Service
public class DefaultSsrfUrlValidator implements SsrfUrlValidator {
  private final DnsResolver resolver;

  public DefaultSsrfUrlValidator() {
    this(InetAddress::getAllByName);
  }

  @Autowired
  public DefaultSsrfUrlValidator(DnsResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public void validate(String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new SsrfValidationException("Malformed endpoint URL");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new SsrfValidationException("URL scheme must be HTTPS");
    }
    if (uri.getRawUserInfo() != null) {
      throw new SsrfValidationException("URL credentials are not allowed");
    }
    if (uri.getFragment() != null)
      throw new SsrfValidationException("URL fragments are not allowed");
    if (uri.getPort() != -1 && uri.getPort() != 443) {
      throw new SsrfValidationException("Only port 443 is allowed");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) throw new SsrfValidationException("URL host is required");
    host = host.toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
    if (host.contains("%")) throw new SsrfValidationException("Scoped addresses are not allowed");
    // ofLiteral never resolves DNS. Java converts mapped IPv6 to Inet4Address, so reject
    // the original IPv6 spelling before that conversion can hide its address family.
    try {
      InetAddress literal = InetAddress.ofLiteral(host);
      if (host.contains(":") && literal instanceof Inet4Address) reject();
      validateAddress(literal);
      return;
    } catch (IllegalArgumentException exception) {
      if (exception instanceof SsrfValidationException validation) throw validation;
    }
    if (host.equals("localhost")
        || host.endsWith(".localhost")
        || host.endsWith(".local")
        || host.endsWith(".internal")
        || host.equals("metadata.google.internal")) reject();
    final InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException exception) {
      throw new SsrfValidationException("Unable to resolve endpoint hostname");
    }
    if (addresses == null || addresses.length == 0) {
      throw new SsrfValidationException("Unable to resolve endpoint hostname");
    }
    for (InetAddress address : addresses) validateAddress(address);
  }

  public static void validateAddress(InetAddress address) {
    if (address == null
        || address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()
        || address.isSiteLocalAddress()) reject();
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 255;
    int second = bytes[1] & 255;
    if (address instanceof Inet4Address) {
      int third = bytes[2] & 255;
      if (first == 0
          || first >= 224
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 192 && second == 0 && (third == 0 || third == 2))
          || (first == 192 && second == 88 && third == 99)
          || (first == 198 && (second == 18 || second == 19))
          || (first == 198 && second == 51 && third == 100)
          || (first == 203 && second == 0 && third == 113)) reject();
    } else if (address instanceof Inet6Address) {
      // Permit global unicast 2000::/3, excluding protocol assignments, documentation,
      // deprecated 6to4 and the 3fff::/20 documentation prefix. This also rejects
      // local/translation/mapped ranges rather than relying on Java's site-local test.
      if ((first & 0xe0) != 0x20
          || (first == 0x20 && second == 0x01 && (bytes[2] & 255) < 2)
          || (first == 0x20
              && second == 0x01
              && (bytes[2] & 255) == 0x0d
              && (bytes[3] & 255) == 0xb8)
          || (first == 0x20 && second == 0x02)
          || (first == 0x3f && second == 0xff && (bytes[2] & 0xf0) == 0)) reject();
    } else reject();
  }

  private static void reject() {
    throw new SsrfValidationException("URL host is not allowed");
  }
}
