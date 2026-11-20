package com.hookrelay.delivery.transport;

import com.hookrelay.security.ssrf.SsrfUrlValidator;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * SSRF-safe outbound webhook transport.
 *
 * <p>Validates DNS at connection time: every resolved address must pass the public-address policy
 * before a socket is opened. Connections are pinned to validated addresses only, with TLS hostname
 * verification preserved against the original hostname. No redirects, no cookies, no automatic
 * retries, no connection reuse, bounded timeouts and bounded response-body consumption.
 */
@Component
@EnableConfigurationProperties(WebhookTransportProperties.class)
public class SsrfSafeWebhookTransport implements WebhookTransport {

  private final RestClient restClient;
  private final SsrfUrlValidator urlValidator;
  private final com.hookrelay.security.ssrf.DnsResolver dnsResolver;
  private final WebhookTransportProperties properties;

  @Autowired
  public SsrfSafeWebhookTransport(
      SsrfUrlValidator urlValidator,
      com.hookrelay.security.ssrf.DnsResolver dnsResolver,
      WebhookTransportProperties properties) {
    this(buildRestClient(properties), urlValidator, dnsResolver, properties);
  }

  /** Package-visible constructor for testing with an injected RestClient. */
  SsrfSafeWebhookTransport(
      RestClient restClient,
      SsrfUrlValidator urlValidator,
      com.hookrelay.security.ssrf.DnsResolver dnsResolver,
      WebhookTransportProperties properties) {
    this.restClient = restClient;
    this.urlValidator = urlValidator;
    this.dnsResolver = dnsResolver;
    this.properties = properties;
  }

  private static RestClient buildRestClient(WebhookTransportProperties properties) {
    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionReuseStrategy((request, response, context) -> false)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAuthCaching()
            .evictExpiredConnections()
            .build();

    HttpComponentsClientHttpRequestFactory requestFactory =
        new HttpComponentsClientHttpRequestFactory(httpClient);
    requestFactory.setConnectionRequestTimeout((int) properties.connectTimeout().toMillis());
    requestFactory.setReadTimeout((int) properties.responseTimeout().toMillis());

    return RestClient.builder().requestFactory(requestFactory).build();
  }

  @Override
  public TransportResult send(String url, byte[] body) throws TransportException {
    try {
      // Defense in depth: validate URL at delivery time too.
      urlValidator.validate(url);

      // Resolve and validate DNS at connection time. Every address must pass the
      // public-address policy; a single unsafe address rejects the hostname.
      String host = extractHost(url);
      InetAddress[] addresses;
      try {
        addresses = dnsResolver.resolve(host);
      } catch (UnknownHostException e) {
        throw new TransportException("Unable to resolve endpoint hostname: " + host, e);
      }
      if (addresses == null || addresses.length == 0) {
        throw new TransportException("No addresses resolved for " + host);
      }
      for (InetAddress address : addresses) {
        SsrfAddressValidator.validate(address);
      }

      return restClient
          .post()
          .uri(url)
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(body)
          .exchange(
              (request, response) -> {
                int statusCode = response.getStatusCode().value();
                String responseBody =
                    readBounded(response.getBody(), properties.maxResponseBodyBytes());
                return new TransportResult(statusCode, responseBody, Instant.now());
              },
              false);
    } catch (Exception e) {
      throw new TransportException("Transport failure: " + e.getMessage(), e);
    }
  }

  private static String extractHost(String url) throws TransportException {
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      throw new TransportException("Malformed endpoint URL: " + url, e);
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new TransportException("URL host is required");
    }
    return host;
  }

  private String readBounded(InputStream inputStream, int maxBytes) {
    if (inputStream == null) {
      return "";
    }
    try {
      byte[] buffer = new byte[maxBytes + 1];
      int read = inputStream.read(buffer);
      if (read <= 0) {
        return "";
      }
      if (read > maxBytes) {
        return new String(buffer, 0, maxBytes, StandardCharsets.UTF_8) + " [truncated]";
      }
      return new String(buffer, 0, read, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "[unable to read response: " + e.getMessage() + "]";
    }
  }
}
