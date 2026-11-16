package com.hookrelay.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.hookrelay.auth.ApiKeyAuthenticationFilter;
import com.hookrelay.auth.ProjectRateLimiter;
import com.hookrelay.auth.RateLimitProperties;
import com.hookrelay.common.RequestBodySizeLimitFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class HttpSecurityIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.7-alpine");

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
    r.add("management.endpoints.web.exposure.include", () -> "health");
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private ProjectRateLimiter rateLimiter;
  @Autowired private ApiKeyAuthenticationFilter apiKeyAuthFilter;
  @Autowired private RequestBodySizeLimitFilter bodySizeFilter;

  private MockMvc mvc;
  private UUID projectA;
  private UUID projectB;

  @BeforeEach
  void seed() {
    mvc =
        webAppContextSetup(context)
            .addFilter(apiKeyAuthFilter, "/*")
            .addFilter(bodySizeFilter, "/*")
            .build();
    UUID org = UUID.randomUUID();
    projectA = UUID.randomUUID();
    projectB = UUID.randomUUID();
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
        com.hookrelay.auth.ApiKeyAuthService.sha256(KEY_A));
    jdbc.update(
        "INSERT INTO api_keys(id,project_id,name,key_prefix,key_hash) VALUES (?,?,'test','testbbb',?)",
        UUID.randomUUID(),
        projectB,
        com.hookrelay.auth.ApiKeyAuthService.sha256(KEY_B));
  }

  private String endpoint(String key) throws Exception {
    return mapper
        .readTree(
            mvc.perform(
                    post("/v1/endpoints")
                        .header("X-Api-Key", key)
                        .contentType("application/json")
                        .content("{\"url\":\"https://8.8.8.8/" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Cache-Control"))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asText();
  }

  @Test
  void authenticatesAndRejectsInvalidKeysThroughFilter() throws Exception {
    mvc.perform(get("/v1/endpoints")).andExpect(status().isUnauthorized());
    mvc.perform(get("/v1/endpoints").header("X-Api-Key", "invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/v1/endpoints").header("X-Api-Key", KEY_A)).andExpect(status().isOk());
  }

  @Test
  void enforcesTenantIsolationAndSecretRedactionOverHttp() throws Exception {
    String id = endpoint(KEY_A);
    String path = "/v1/endpoints/" + id;
    mvc.perform(delete(path).header("X-Api-Key", KEY_B)).andExpect(status().isNotFound());
    mvc.perform(
            patch(path).header("X-Api-Key", KEY_B).content("{}").contentType("application/json"))
        .andExpect(status().isNotFound());
    mvc.perform(post(path + "/enable").header("X-Api-Key", KEY_B)).andExpect(status().isNotFound());
    mvc.perform(post(path + "/disable").header("X-Api-Key", KEY_B))
        .andExpect(status().isNotFound());
    mvc.perform(post(path + "/rotate-secret").header("X-Api-Key", KEY_B))
        .andExpect(status().isNotFound());
    mvc.perform(put(path + "/subscriptions/test.event").header("X-Api-Key", KEY_B))
        .andExpect(status().isNotFound());

    var body =
        mvc.perform(get(path).header("X-Api-Key", KEY_A))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("whsec_", "signingSecret", "ciphertext", "nonce");

    var rotated =
        mvc.perform(post(path + "/rotate-secret").header("X-Api-Key", KEY_A))
            .andExpect(status().isOk())
            .andExpect(header().exists("Cache-Control"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rotated).doesNotContain("ciphertext", "nonce");
  }

  @Test
  void rejectsOversizedBodiesOnEveryWriteApi() throws Exception {
    String big = "{\"description\":\"" + "a".repeat(2000) + "\"}";
    String[] paths = {"/v1/events", "/v1/endpoints", "/v1/endpoints/" + UUID.randomUUID()};
    String[] methods = {"POST", "POST", "PATCH"};
    for (int i = 0; i < paths.length; i++) {
      var builder = methods[i].equals("POST") ? post(paths[i]) : patch(paths[i]);
      mvc.perform(
              builder
                  .header("X-Api-Key", KEY_A)
                  .contentType("application/json")
                  .content(big.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .andExpect(status().is4xxClientError());
    }
  }

  @Test
  void managementMetricsAreNotPubliclyExposed() throws Exception {
    for (var path : new String[] {"metrics", "prometheus", "info"}) {
      mvc.perform(get("/actuator/" + path)).andExpect(status().isNotFound());
    }
  }

  @Test
  void rateLimiterAllowsExactQuotaAndRecoversAfterWindow() throws Exception {
    var limited = new ProjectRateLimiter(jdbc, new RateLimitProperties(5, 60));
    var results = new java.util.ArrayList<Integer>();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 20; i++) {
        futures.add(executor.submit(() -> limited.acquire(projectA)));
      }
      for (var f : futures) results.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(results.stream().filter(v -> v == 0)).hasSize(5);

    jdbc.update(
        "UPDATE project_request_limits SET window_end=clock_timestamp()-interval '1 second' WHERE project_id=?",
        projectA);
    assertThat(rateLimiter.acquire(projectA)).isZero();
  }
}
