package com.hookrelay.delivery.transport;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the SSRF-safe outbound webhook transport.
 *
 * <p>All timeouts are enforced by the underlying HTTP client. The transport does not retain
 * response bodies beyond the configured byte cap.
 */
@ConfigurationProperties("hookrelay.delivery.transport")
public record WebhookTransportProperties(
    Duration connectTimeout,
    Duration responseTimeout,
    Duration totalTimeout,
    int maxResponseBodyBytes,
    int maxConnections,
    int maxConnectionsPerRoute) {

  public WebhookTransportProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
    responseTimeout = responseTimeout == null ? Duration.ofSeconds(5) : responseTimeout;
    totalTimeout = totalTimeout == null ? Duration.ofSeconds(15) : totalTimeout;
    maxResponseBodyBytes = maxResponseBodyBytes <= 0 ? 8192 : maxResponseBodyBytes;
    maxConnections = maxConnections <= 0 ? 16 : maxConnections;
    maxConnectionsPerRoute = maxConnectionsPerRoute <= 0 ? 4 : maxConnectionsPerRoute;

    if (connectTimeout.isNegative()
        || responseTimeout.isNegative()
        || totalTimeout.isNegative()
        || maxResponseBodyBytes > 1048576
        || maxConnections < 1
        || maxConnectionsPerRoute < 1) {
      throw new IllegalArgumentException("Invalid transport configuration");
    }
  }
}
