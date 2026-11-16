package com.hookrelay.security.ssrf;

public interface SsrfUrlValidator {

  /**
   * Validates a URL against SSRF security policy.
   *
   * @param url the URL to validate
   * @throws SsrfValidationException if the URL violates the security policy
   */
  void validate(String url);
}
