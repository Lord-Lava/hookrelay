package com.hookrelay.delivery.transport;

import com.hookrelay.security.ssrf.DefaultSsrfUrlValidator;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.net.InetAddress;

/**
 * Validates individual resolved IP addresses at connection time.
 *
 * <p>All DNS answers must pass this policy; a single unsafe address causes the entire hostname to
 * be rejected, preventing DNS rebinding and mixed-answer attacks. Only public, globally routable
 * addresses are permitted.
 *
 * <p>Delegates to {@link DefaultSsrfUrlValidator#validateAddress(InetAddress)} so the same address
 * policy drives both registration-time and delivery-time enforcement.
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
    DefaultSsrfUrlValidator.validateAddress(address);
  }
}
