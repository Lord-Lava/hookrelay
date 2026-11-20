package com.hookrelay.delivery.transport;

import com.hookrelay.security.ssrf.SsrfValidationException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Validates individual resolved IP addresses at connection time.
 *
 * <p>All DNS answers must pass this policy; a single unsafe address causes the entire hostname to
 * be rejected, preventing DNS rebinding and mixed-answer attacks. Only public, globally routable
 * addresses are permitted.
 */
public class SsrfAddressValidator {

  private SsrfAddressValidator() {}

  /**
   * Validate a single resolved address. Throws if the address is loopback, private, link-local,
   * multicast, reserved, or otherwise not a public globally-routable address.
   *
   * @param address the address to validate
   * @throws SsrfValidationException if the address is not allowed
   */
  public static void validate(InetAddress address) throws SsrfValidationException {
    if (address == null) {
      throw new SsrfValidationException("URL host is not allowed");
    }
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()
        || address.isSiteLocalAddress()) {
      reject();
    }
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 255;
    int second = bytes[1] & 255;
    if (address instanceof Inet4Address) {
      validateIPv4(bytes, first, second);
    } else if (address instanceof Inet6Address) {
      validateIPv6(bytes, first, second);
    } else {
      reject();
    }
  }

  private static void validateIPv4(byte[] bytes, int first, int second) {
    int third = bytes[2] & 255;
    if (first == 0
        || first >= 224
        || (first == 100 && second >= 64 && second <= 127)
        || (first == 192 && second == 0 && (third == 0 || third == 2))
        || (first == 192 && second == 88 && third == 99)
        || (first == 198 && (second == 18 || second == 19))
        || (first == 198 && second == 51 && third == 100)
        || (first == 203 && second == 0 && third == 113)) {
      reject();
    }
  }

  private static void validateIPv6(byte[] bytes, int first, int second) {
    // Permit global unicast 2000::/3, excluding protocol assignments, documentation,
    // deprecated 6to4 and the 3fff::/20 documentation prefix.
    if ((first & 0xE0) != 0x20) {
      reject();
    }
    if (first == 0x20 && second == 0x01 && (bytes[2] & 255) < 2) {
      reject();
    }
    if (first == 0x20 && second == 0x01 && (bytes[2] & 255) == 0x0D && (bytes[3] & 255) == 0xB8) {
      reject();
    }
    if (first == 0x20 && second == 0x02) {
      reject();
    }
    if (first == 0x3F && second == 0xFF && (bytes[2] & 0xF0) == 0) {
      reject();
    }
  }

  private static void reject() {
    throw new SsrfValidationException("URL host is not allowed");
  }
}
