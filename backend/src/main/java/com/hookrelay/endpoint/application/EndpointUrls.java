package com.hookrelay.endpoint.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Applied after security validation; preserves path/query bytes and case. */
final class EndpointUrls {
  private static final int MAX_URL_BYTES = 2000;

  private EndpointUrls() {}

  static String canonical(String url) {
    URI uri = URI.create(url);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    String path = uri.getRawPath();
    String canonical =
        "https://"
            + host
            + (path == null || path.isEmpty() ? "/" : path)
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES) {
      throw new IllegalArgumentException("URL must not exceed " + MAX_URL_BYTES + " UTF-8 bytes");
    }
    return canonical;
  }
}
