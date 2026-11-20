package com.hookrelay.delivery.transport;

import com.hookrelay.security.ssrf.DnsResolver;
import com.hookrelay.security.ssrf.SsrfUrlValidator;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

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
public class SsrfSafeWebhookTransport implements WebhookTransport, DisposableBean {

  private final CloseableHttpClient httpClient;
  private final SsrfUrlValidator urlValidator;
  private final DnsResolver dnsResolver;
  private final WebhookTransportProperties properties;

  @Autowired
  public SsrfSafeWebhookTransport(
      SsrfUrlValidator urlValidator,
      DnsResolver dnsResolver,
      WebhookTransportProperties properties) {
    this.httpClient = buildHttpClient(properties);
    this.urlValidator = urlValidator;
    this.dnsResolver = dnsResolver;
    this.properties = properties;
  }

  /** Package-visible constructor for testing with an injected HttpClient. */
  SsrfSafeWebhookTransport(
      CloseableHttpClient httpClient,
      SsrfUrlValidator urlValidator,
      DnsResolver dnsResolver,
      WebhookTransportProperties properties) {
    this.httpClient = httpClient;
    this.urlValidator = urlValidator;
    this.dnsResolver = dnsResolver;
    this.properties = properties;
  }

  private static CloseableHttpClient buildHttpClient(WebhookTransportProperties properties) {
    PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(properties.maxConnections());
    connectionManager.setDefaultMaxPerRoute(properties.maxConnectionsPerRoute());

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(
                Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
            .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
            .setResponseTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis()))
            .build();

    return HttpClients.custom()
        .setConnectionManager(connectionManager)
        .setDefaultRequestConfig(requestConfig)
        .setConnectionReuseStrategy((request, response, context) -> false)
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .disableCookieManagement()
        .disableAuthCaching()
        .evictExpiredConnections()
        .evictIdleConnections(TimeValue.ofMilliseconds(0))
        .build();
  }

  private static final Set<String> RESERVED_HEADERS =
      Set.of(
          "Host",
          "Authorization",
          "Cookie",
          "Content-Length",
          "Content-Type",
          "Transfer-Encoding",
          "TE",
          "Trailer",
          "Upgrade",
          "Connection",
          "Keep-Alive",
          "Proxy-Authorization",
          "Proxy-Connection",
          "Sec-WebSocket-Key",
          "Sec-WebSocket-Version",
          "Sec-WebSocket-Accept",
          "Sec-WebSocket-Extensions",
          "Sec-WebSocket-Protocol");

  private static final Set<String> SIGNING_HEADERS =
      Set.of("webhook-id", "webhook-timestamp", "webhook-signature");

  @Override
  public TransportResult send(String url, byte[] body) throws TransportException {
    return send(url, body, Map.of());
  }

  /**
   * Sends an HTTP POST request with additional headers.
   *
   * @param url the destination URL (HTTPS only, port 443)
   * @param body the request body bytes
   * @param headers additional HTTP headers to include in the request; only {@code webhook-id},
   *     {@code webhook-timestamp} and {@code webhook-signature} are accepted, and
   *     reserved/transport headers are rejected
   * @return the transport result with status code and bounded body preview
   * @throws TransportException if the request cannot be sent safely or fails
   */
  public TransportResult send(String url, byte[] body, Map<String, String> headers)
      throws TransportException {
    try {
      // Defense in depth: validate URL at delivery time too.
      urlValidator.validate(url);

      // Resolve DNS at connection time. Every resolved address must pass the public-address
      // policy before we connect. This prevents DNS rebinding: we never re-resolve the hostname.
      URI uri = new URI(url);
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        throw new TransportException("URL host is required");
      }
      int port = uri.getPort() == -1 ? 443 : uri.getPort();
      String path = uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "");

      InetAddress[] addresses;
      try {
        addresses = dnsResolver.resolve(host);
      } catch (UnknownHostException e) {
        throw new TransportException("Unable to resolve endpoint hostname: " + host, e);
      }
      if (addresses == null || addresses.length == 0) {
        throw new TransportException("No addresses resolved for " + host);
      }
      // Validate every DNS answer; a single unsafe address rejects the hostname entirely.
      for (InetAddress address : addresses) {
        SsrfAddressValidator.validate(address);
      }

      // Pin the connection to the first validated address. Pass the InetAddress directly
      // so HttpClient connects to the IP without re-resolving DNS. Preserve the original
      // hostname in the "Host" header for TLS SNI/verification.
      InetAddress selectedAddress = selectAddress(addresses);
      HttpHost target = new HttpHost(selectedAddress, port);

      HttpPost httpPost = new HttpPost(path);
      httpPost.setHeader("Host", uri.getHost());
      httpPost.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
      if (headers != null) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
          String lower = entry.getKey().toLowerCase(Locale.ROOT);
          if (RESERVED_HEADERS.contains(lower)) {
            throw new TransportException("Reserved header is not allowed: " + entry.getKey());
          }
          if (!SIGNING_HEADERS.contains(lower)) {
            throw new TransportException("Header is not allowed: " + entry.getKey());
          }
          httpPost.setHeader(entry.getKey(), entry.getValue());
        }
      }

      try (CloseableHttpResponse response = httpClient.execute(target, httpPost)) {
        int statusCode = response.getCode();
        String responseBody = readBounded(response.getEntity(), properties.maxResponseBodyBytes());
        return new TransportResult(statusCode, responseBody, Instant.now());
      }
    } catch (SsrfValidationException e) {
      // Permanent policy failure - URL validation or DNS address policy rejected.
      throw new TransportException("URL validation failed: " + e.getMessage(), e);
    } catch (UnknownHostException e) {
      // Permanent infrastructure failure - DNS cannot resolve.
      throw new TransportException("Unable to resolve endpoint hostname", e);
    } catch (SSLException e) {
      // Permanent security failure - TLS hostname or certificate verification failed.
      throw new TransportException("TLS verification failed: " + e.getMessage(), e);
    } catch (IOException e) {
      // Transient infrastructure failure - may be retried by caller.
      throw new TransportException("Transport I/O failure: " + e.getMessage(), e);
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

  private static InetAddress selectAddress(InetAddress[] addresses) {
    // Prefer IPv4 over IPv6 for compatibility; fall back to the first address.
    for (InetAddress address : addresses) {
      if (address instanceof Inet4Address) {
        return address;
      }
    }
    return addresses[0];
  }

  private String readBounded(HttpEntity entity, int maxBytes) throws TransportException {
    if (entity == null) {
      return "";
    }
    try (InputStream inputStream = entity.getContent()) {
      if (inputStream == null) {
        return "";
      }
      byte[] buffer = new byte[maxBytes + 1];
      int totalRead = 0;
      int read;
      while ((read = inputStream.read(buffer, totalRead, buffer.length - totalRead)) > 0) {
        totalRead += read;
        if (totalRead > maxBytes) {
          return new String(buffer, 0, maxBytes, StandardCharsets.UTF_8) + " [truncated]";
        }
      }
      return new String(buffer, 0, totalRead, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TransportException("Failed to read response body: " + e.getMessage(), e);
    }
  }

  @Override
  public void destroy() {
    try {
      httpClient.close();
    } catch (IOException e) {
      // Best-effort close; log and continue.
    }
  }
}
