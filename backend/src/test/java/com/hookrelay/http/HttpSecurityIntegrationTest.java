package com.hookrelay.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.hookrelay.auth.ApiKeyAuthService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HttpSecurityIntegrationTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.7-alpine");

  private static final String KEY_A = "hr_live_testaaa_abcdefghijklmnopqrstuvwx";
  private static final String KEY_B = "hr_live_testbbb_abcdefghijklmnopqrstuvwx";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("hookrelay.outbox.enabled", () -> false);
    r.add("hookrelay.signing.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    r.add("hookrelay.rate-limit.requests", () -> 10);
    r.add("hookrelay.http.max-request-bytes", () -> 1024);
    r.add("server.servlet.context-path", () -> "/relay");
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  private HttpClient client;
  private UUID projectA;

  @BeforeEach
  void seed() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    UUID org = UUID.randomUUID();
    projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();
    jdbc.update("DELETE FROM api_keys WHERE key_prefix IN ('testaaa','testbbb')");
    jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'http-test')", org);
    for (UUID project : new UUID[] {projectA, projectB}) {
      jdbc.update(
          "INSERT INTO projects(id,organization_id,name,environment) VALUES (?,?,?,'development')",
          project,
          org,
          project.toString());
    }
    jdbc.update(
        "INSERT INTO api_keys(id,project_id,name,key_prefix,key_hash) VALUES (?,?,'test','testaaa',?)",
        UUID.randomUUID(),
        projectA,
        ApiKeyAuthService.sha256(KEY_A));
    jdbc.update(
        "INSERT INTO api_keys(id,project_id,name,key_prefix,key_hash) VALUES (?,?,'test','testbbb',?)",
        UUID.randomUUID(),
        projectB,
        ApiKeyAuthService.sha256(KEY_B));
  }

  @AfterEach
  void closeClient() {
    client.close();
  }

  private HttpResponse<String> request(String method, String path, String key, String body)
      throws Exception {
    return request(
        method,
        path,
        key,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
  }

  private HttpResponse<String> request(
      String method, String path, String key, HttpRequest.BodyPublisher body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/relay" + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json");
    if (key != null) builder.header("X-Api-Key", key);
    return client.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

  private void problem(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
    assertThat(json(response).get("status").asInt()).isEqualTo(status);
    assertThat(json(response).get("detail").asString()).isNotBlank();
  }

  private String endpoint() throws Exception {
    var response =
        request(
            "POST",
            "/v1/endpoints",
            KEY_A,
            "{\"url\":\"https://8.8.8.8/" + UUID.randomUUID() + "\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(json(response).get("signingSecret").asString()).startsWith("whsec_");
    return json(response).get("id").asString();
  }

  @Test
  void authenticatesAndRejectsRevokedKeysThroughRegisteredFilters() throws Exception {
    problem(request("GET", "/v1/endpoints", null, ""), 401);
    problem(request("GET", "/v1/endpoints", "invalid", ""), 401);
    assertThat(request("GET", "/v1/endpoints", KEY_A, "").statusCode()).isEqualTo(200);
    jdbc.update("UPDATE api_keys SET revoked_at=clock_timestamp() WHERE project_id=?", projectA);
    problem(request("GET", "/v1/endpoints", KEY_A, ""), 401);
  }

  @Test
  void isolatesAnExistingEndpointAcrossEveryOperation() throws Exception {
    String path = "/v1/endpoints/" + endpoint();
    assertThat(request("PUT", path + "/subscriptions/test.event", KEY_A, "").statusCode())
        .isEqualTo(201);
    for (var method : new String[] {"GET", "PATCH", "DELETE"}) {
      problem(request(method, path, KEY_B, "{}"), 404);
    }
    for (var operation : new String[] {"enable", "disable", "rotate-secret"}) {
      problem(request("POST", path + "/" + operation, KEY_B, ""), 404);
    }
    problem(request("PUT", path + "/subscriptions/test.event", KEY_B, ""), 404);
    problem(request("DELETE", path + "/subscriptions/test.event", KEY_B, ""), 404);
    problem(request("GET", path + "/subscriptions", KEY_B, ""), 404);
    var foreignList = request("GET", "/v1/endpoints", KEY_B, "");
    assertThat(foreignList.statusCode()).isEqualTo(200);
    assertThat(json(foreignList).get("endpoints").isEmpty()).isTrue();
    var owned = request("GET", path, KEY_A, "");
    assertThat(owned.statusCode()).isEqualTo(200);
    assertThat(json(owned).get("enabled").asBoolean()).isTrue();
    var subscriptions = request("GET", path + "/subscriptions", KEY_A, "");
    assertThat(json(subscriptions).get("total").asInt()).isEqualTo(1);
    assertThat(owned.body()).doesNotContain("whsec_", "signingSecret", "ciphertext", "nonce");
    var rotated = request("POST", path + "/rotate-secret", KEY_A, "");
    assertThat(rotated.statusCode()).isEqualTo(200);
    assertThat(rotated.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(json(rotated).get("signingSecret").asString()).startsWith("whsec_");
  }

  @Test
  void returns413ForKnownAndUnknownLengthBodiesOnEveryWriteApi() throws Exception {
    byte[] big =
        ("{\"description\":\"" + "a".repeat(2000) + "\"}").getBytes(StandardCharsets.UTF_8);
    String endpointPath = "/v1/endpoints/" + endpoint();
    String[] paths = {"/v1/events", "/v1/endpoints", endpointPath};
    String[] methods = {"POST", "POST", "PATCH"};
    for (int i = 0; i < paths.length; i++) {
      problem(
          request(methods[i], paths[i], KEY_A, HttpRequest.BodyPublishers.ofByteArray(big)), 413);
      var streamed = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big));
      assertThat(streamed.contentLength()).isEqualTo(-1);
      problem(request(methods[i], paths[i], KEY_A, streamed), 413);
    }
  }

  @Test
  void validJsonReportsFieldLengthValidation() throws Exception {
    var body =
        mapper
            .createObjectNode()
            .put("url", "https://8.8.8.8/hook")
            .put("description", "a".repeat(161));
    var response = request("POST", "/v1/endpoints", KEY_A, mapper.writeValueAsString(body));
    problem(response, 400);
    assertThat(json(response).get("errors")).as(response.body()).isNotNull();
    assertThat(json(response).get("errors").get("description").asString())
        .contains("must not exceed");
  }

  @Test
  void managementMetricsAreNotPubliclyExposed() throws Exception {
    for (var path : new String[] {"metrics", "prometheus", "info"}) {
      assertThat(request("GET", "/actuator/" + path, null, "").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void concurrentHttpRequestsReturnExactQuotaAndRetryAfterThenRecover() throws Exception {
    var responses = new ArrayList<HttpResponse<String>>();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 24; i++) {
        futures.add(executor.submit(() -> request("GET", "/v1/endpoints", KEY_A, "")));
      }
      for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
    }
    assertThat(responses.stream().filter(r -> r.statusCode() == 200)).hasSize(10);
    assertThat(responses.stream().filter(r -> r.statusCode() == 429)).hasSize(14);
    for (var response : responses) {
      if (response.statusCode() != 429) continue;
      problem(response, 429);
      int retryAfter = Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow());
      assertThat(retryAfter).isBetween(1, 60);
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    assertThat(request("GET", "/v1/endpoints", KEY_B, "").statusCode()).isEqualTo(200);
    jdbc.update(
        "UPDATE project_request_limits SET window_end=clock_timestamp()-interval '1 second' WHERE project_id=?",
        projectA);
    assertThat(request("GET", "/v1/endpoints", KEY_A, "").statusCode()).isEqualTo(200);
  }
}
