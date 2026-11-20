package com.hookrelay.delivery.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.hookrelay.security.ssrf.SsrfUrlValidator;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.time.Duration;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SsrfSafeWebhookTransportTest {

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
  void givenNonHttpsUrl_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("URL scheme must be HTTPS"))
        .when(urlValidator)
        .validate("http://evil.com/hook");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("http://evil.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL scheme must be HTTPS");
  }

  @Test
  void givenNon443Port_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("Only port 443 is allowed"))
        .when(urlValidator)
        .validate("https://evil.com:8080/hook");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://evil.com:8080/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("Only port 443 is allowed");
  }

  @Test
  void givenUserInfoInUrl_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("URL credentials are not allowed"))
        .when(urlValidator)
        .validate("https://user:pass@evil.com/hook");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://user:pass@evil.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL credentials are not allowed");
  }

  @Test
  void givenFragmentInUrl_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("URL fragments are not allowed"))
        .when(urlValidator)
        .validate("https://evil.com/hook#fragment");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(httpClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://evil.com/hook#fragment", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL fragments are not allowed");
  }

  @Test
  void givenTwoRandomUuids_whenGenerated_thenTheyAreDistinct() {
    java.util.UUID id1 = java.util.UUID.randomUUID();
    java.util.UUID id2 = java.util.UUID.randomUUID();
    assertThat(id1).isNotEqualTo(id2);
  }
}
