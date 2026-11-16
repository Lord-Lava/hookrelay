package com.hookrelay.endpoint.application;

import java.net.URI;
import java.util.Locale;

/** Applied after security validation; preserves path/query bytes and case. */
final class EndpointUrls {
  private EndpointUrls() {}

  static String canonical(String url) {
    URI uri = URI.create(url);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    String path = uri.getRawPath();
    return "https://"
        + host
        + (path == null || path.isEmpty() ? "/" : path)
        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }
}
