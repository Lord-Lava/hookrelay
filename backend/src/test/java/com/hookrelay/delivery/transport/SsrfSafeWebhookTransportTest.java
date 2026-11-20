package com.hookrelay.delivery.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hookrelay.security.ssrf.SsrfUrlValidator;
import com.hookrelay.security.ssrf.SsrfValidationException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SsrfSafeWebhookTransportTest {

  private SsrfUrlValidator urlValidator;
  private com.hookrelay.security.ssrf.DnsResolver dnsResolver;
  private WebhookTransportProperties properties;
  private RestClient restClient;

  @BeforeEach
  void setUp() {
    urlValidator = mock(SsrfUrlValidator.class);
    dnsResolver = mock(com.hookrelay.security.ssrf.DnsResolver.class);
    properties =
        new WebhookTransportProperties(
            Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(15), 8192, 16, 4);
    restClient = mock(RestClient.class);
  }

  @Test
  void givenNonHttpsUrl_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("URL scheme must be HTTPS"))
        .when(urlValidator)
        .validate("http://evil.com/hook");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("http://evil.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL scheme must be HTTPS");

    verify(restClient, never()).post();
  }

  @Test
  void givenNon443Port_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("Only port 443 is allowed"))
        .when(urlValidator)
        .validate("https://evil.com:8080/hook");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://evil.com:8080/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("Only port 443 is allowed");

    verify(restClient, never()).post();
  }

  @Test
  void givenUserInfoInUrl_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("URL credentials are not allowed"))
        .when(urlValidator)
        .validate("https://user:pass@evil.com/hook");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://user:pass@evil.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL credentials are not allowed");

    verify(restClient, never()).post();
  }

  @Test
  void givenFragmentInUrl_whenSend_thenRejectedWithTransportException() {
    doThrow(new SsrfValidationException("URL fragments are not allowed"))
        .when(urlValidator)
        .validate("https://evil.com/hook#fragment");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://evil.com/hook#fragment", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL fragments are not allowed");

    verify(restClient, never()).post();
  }

  @Test
  void givenPrivateDnsAnswer_whenSend_thenRejectedWithTransportException() throws Exception {
    doNothing().when(urlValidator).validate("https://8.8.8.8/hook");

    when(dnsResolver.resolve("evil.com"))
        .thenReturn(new InetAddress[] {InetAddress.getByName("10.0.0.1")});

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://evil.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL host is not allowed");

    verify(restClient, never()).post();
  }

  @Test
  void givenMixedDnsAnswers_whenSend_thenRejectedWithTransportException() throws Exception {
    doNothing().when(urlValidator).validate("https://example.com/hook");

    when(dnsResolver.resolve("example.com"))
        .thenReturn(
            new InetAddress[] {
              InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")
            });

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://example.com/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("URL host is not allowed");

    verify(restClient, never()).post();
  }

  @Test
  void givenUnresolvableHost_whenSend_thenRejectedWithTransportException() throws Exception {
    doNothing().when(urlValidator).validate("https://nonexistent.invalid/hook");

    doThrow(new UnknownHostException("Unable to resolve"))
        .when(dnsResolver)
        .resolve("nonexistent.invalid");

    SsrfSafeWebhookTransport transport =
        new SsrfSafeWebhookTransport(restClient, urlValidator, dnsResolver, properties);

    assertThatThrownBy(() -> transport.send("https://nonexistent.invalid/hook", new byte[0]))
        .isInstanceOf(TransportException.class)
        .hasMessageContaining("Unable to resolve endpoint hostname");

    verify(restClient, never()).post();
  }

  @Test
  void givenTwoRandomUuids_whenGenerated_thenTheyAreDistinct() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    assertThat(id1).isNotEqualTo(id2);
  }
}
