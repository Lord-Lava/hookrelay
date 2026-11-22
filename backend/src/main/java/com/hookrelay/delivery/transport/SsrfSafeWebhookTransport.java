package com.hookrelay.delivery.transport;

import com.hookrelay.delivery.domain.AttemptOutcome;
import com.hookrelay.security.ssrf.DnsResolver;
import com.hookrelay.security.ssrf.SsrfUrlValidator;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
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

  private static final String ORIGINAL_HOSTNAME_ATTR = "hookrelay.ssl.originalHostname";

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
    SSLContext sslContext;
    try {
      sslContext = SSLContext.getDefault();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Default SSLContext unavailable", e);
    }
    // Use the JDK's default hostname verifier against the original URL hostname (not the resolved
    // IP address). Apache HttpClient enforces verification during the TLS handshake via
    // SSLConnectionSocketFactory.verifyHostname(), throwing SSLPeerUnverifiedException on mismatch.
    SSLConnectionSocketFactory sslSocketFactory =
        new SSLConnectionSocketFactory(sslContext, HttpsURLConnection.getDefaultHostnameVerifier());
    TlsSocketStrategy tlsSocketStrategy =
        new TlsSocketStrategy() {
          @Override
          public SSLSocket upgrade(
              Socket socket, String hostname, int port, Object attachment, HttpContext context) {
            try {
              // Use the original URL hostname (not the resolved IP) as the TLS peer name so SNI and
              // certificate verification are performed against the expected hostname. The TCP
              // connection is already pinned to the validated IP address; no additional DNS lookup
              // occurs.
              String tlsPeerName = (String) context.getAttribute(ORIGINAL_HOSTNAME_ATTR);
              if (tlsPeerName == null || tlsPeerName.isBlank()) {
                tlsPeerName = hostname;
              }
              return (SSLSocket)
                  sslSocketFactory.createLayeredSocket(socket, tlsPeerName, port, context);
            } catch (IOException e) {
              throw new IllegalStateException("TLS upgrade failed: " + e.getMessage(), e);
            }
          }
        };
    PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setTlsSocketStrategy(tlsSocketStrategy)
            .setMaxConnTotal(properties.maxConnections())
            .setMaxConnPerRoute(properties.maxConnectionsPerRoute())
            .build();

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
      // so HttpClient connects to the IP without re-resolving DNS. Establish TLS to the
      // validated IP while verifying the certificate and hostname against the original
      // hostname (SNI/verification happen before the Host header is sent).
      InetAddress selectedAddress = selectAddress(addresses);
      HttpHost target = new HttpHost("https", selectedAddress.getHostAddress(), port);

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

      HttpClientContext context = HttpClientContext.create();
      context.setAttribute(ORIGINAL_HOSTNAME_ATTR, uri.getHost());

      try (CloseableHttpResponse response = httpClient.execute(target, httpPost, context)) {
        int statusCode = response.getCode();
        String responseBody;
        try {
          responseBody = readBounded(response.getEntity(), properties.maxResponseBodyBytes());
        } catch (TransportException e) {
          // Read failure after HTTP response - preserve HTTP status code outcome
          AttemptOutcome outcome;
          if (statusCode >= 200 && statusCode < 300) {
            outcome = AttemptOutcome.SUCCEEDED;
          } else if (statusCode == 408
              || statusCode == 429
              || (statusCode >= 500 && statusCode < 600)) {
            outcome = AttemptOutcome.RETRYABLE_FAILURE;
          } else {
            outcome = AttemptOutcome.PERMANENT_FAILURE;
          }
          return new TransportResult(
              statusCode, null, Instant.now(), outcome, null, "RESPONSE_READ_FAILED", null);
        }
        Instant sentAt = Instant.now();
        AttemptOutcome outcome;
        if (statusCode >= 200 && statusCode < 300) {
          outcome = AttemptOutcome.SUCCEEDED;
        } else if (statusCode == 408
            || statusCode == 429
            || (statusCode >= 500 && statusCode < 600)) {
          outcome = AttemptOutcome.RETRYABLE_FAILURE;
        } else {
          outcome = AttemptOutcome.PERMANENT_FAILURE;
        }

        // Parse Retry-After hint for retryable responses
        Instant retryAt = null;
        String retryAfterHeader =
            response.getFirstHeader("Retry-After") == null
                ? null
                : response.getFirstHeader("Retry-After").getValue();
        if (retryAfterHeader != null && outcome == AttemptOutcome.RETRYABLE_FAILURE) {
          try {
            long retryAfterSeconds = Long.parseLong(retryAfterHeader.trim());
            if (retryAfterSeconds > 0) {
              retryAt = sentAt.plusSeconds(retryAfterSeconds);
            }
          } catch (NumberFormatException e) {
            // Try HTTP date format
            try {
              retryAt =
                  Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(retryAfterHeader.trim()));
            } catch (DateTimeParseException e2) {
              // Ignore unparseable Retry-After
            }
          }
        }

        long durationMs = Duration.between(sentAt, Instant.now()).toMillis();
        return new TransportResult(
            statusCode, responseBody, sentAt, outcome, durationMs, null, retryAt);
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
