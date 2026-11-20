package com.hookrelay.delivery.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hookrelay.security.ssrf.SsrfUrlValidator;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DnsPinningIntegrationTest {

  private SsrfUrlValidator urlValidator;
  private com.hookrelay.security.ssrf.DnsResolver dnsResolver;
  private WebhookTransportProperties properties;
  private CloseableHttpClient httpClient;

  @BeforeEach
  void setUp() {
    urlValidator = mock(SsrfUrlValidator.class);
    dnsResolver = mock(com.hookrelay.security.ssrf.DnsResolver.class);
    properties =
        new WebhookTransportProperties(
            Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(15), 8192, 16, 4);
    httpClient = mock(CloseableHttpClient.class);
  }

  @Test
  void givenPublicDnsAnswer_whenSend_thenConnectionPinsToValidatedAddress() throws Exception {
    doNothing().when(urlValidator).validate("https://evil.com/hook");

    com.hookrelay.security.ssrf.DnsResolver dnsResolver =
        host -> {
          if ("evil.com".equals(host)) {
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
          }
          throw new UnknownHostException("Unable to resolve: " + host);
        };

    when(httpClient.execute(
            any(HttpHost.class), any(org.apache.hc.core5.http.ClassicHttpRequest.class)))
        .thenAnswer(invocation -> {
          HttpHost target = invocation.getArgument(0);
          assertThat(target.getAddress().getHostAddress()).isEqualTo("8.8.8.8");
          CloseableHttpResponse response = mock(CloseableHttpResponse.class);
          when(response.getCode()).thenReturn(200);
          when(response.getEntity()).thenReturn(null);
          return response;
        });

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    TransportResult result = transport.send("https://evil.com/hook", new byte[] {1, 2, 3});

    assertThat(result.statusCode()).isEqualTo(200);
  }

  @Test
  void givenMixedDnsAnswers_whenSend_thenRejectedWithoutConnectionAttempt() throws Exception {
    doNothing().when(urlValidator).validate("https://example.com/hook");

    com.hookrelay.security.ssrf.DnsResolver dnsResolver =
        host -> {
          if ("example.com".equals(host)) {
            return new InetAddress[] {
              InetAddress.getByName("8.8.8.8"), InetAddress.getByName("224.0.0.1")
            };
          }
          throw new UnknownHostException("Unable to resolve: " + host);
        };

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://example.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL host is not allowed");

    verify(httpClient, never())
        .execute(any(HttpHost.class), any(org.apache.hc.core5.http.ClassicHttpRequest.class));
  }

  @Test
  void givenUnresolvableHost_whenSend_thenRejectedWithoutConnectionAttempt() throws Exception {
    doNothing().when(urlValidator).validate("https://nonexistent.invalid/hook");

    com.hookrelay.security.ssrf.DnsResolver dnsResolver =
        host -> {
          throw new UnknownHostException("Unable to resolve: " + host);
        };

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://nonexistent.invalid/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("Unable to resolve endpoint hostname");

    verify(httpClient, never())
        .execute(any(HttpHost.class), any(org.apache.hc.core5.http.ClassicHttpRequest.class));
  }
}
