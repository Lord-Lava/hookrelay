package com.hookrelay.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.hookrelay.endpoint.api.CreatedEndpointResponse;
import com.hookrelay.endpoint.api.EndpointController;
import com.hookrelay.endpoint.api.EndpointCreateRequest;
import com.hookrelay.endpoint.api.EndpointListResponse;
import com.hookrelay.endpoint.api.EndpointResponse;
import com.hookrelay.endpoint.api.EndpointSubscriptionController;
import com.hookrelay.endpoint.api.EndpointUpdateRequest;
import com.hookrelay.endpoint.api.RotateSecretResponse;
import com.hookrelay.endpoint.api.SubscriptionListResponse;
import com.hookrelay.endpoint.api.SubscriptionResponse;
import com.hookrelay.event.api.AcceptedEventResponse;
import com.hookrelay.event.api.EventController;
import jakarta.validation.constraints.Size;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiContractTest {
  private Map<?, ?> spec;

  @BeforeEach
  void parseContract() throws Exception {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try (var input = Files.newInputStream(Path.of("../docs/openapi.yaml"))) {
      spec = object(new Yaml(new SafeConstructor(options)).load(input));
    }
    assertThat(spec.get("openapi")).isEqualTo("3.1.0");
  }

  private Map<?, ?> object(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<?, ?>) value;
  }

  private Map<?, ?> at(Object value, String... path) {
    for (String key : path) value = object(value).get(key);
    return object(value);
  }

  @Test
  void everyControllerOperationIsDocumentedAndEveryResponseHasADescription() {
    Set<String> implemented = new HashSet<>();
    for (Class<?> controller :
        List.of(
            EndpointController.class,
            EndpointSubscriptionController.class,
            EventController.class)) {
      var root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      assertThat(root).isNotNull();
      for (var method : controller.getDeclaredMethods()) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) continue;
        String[] suffixes = mapping.path().length == 0 ? new String[] {""} : mapping.path();
        for (String prefix : root.path()) {
          for (String suffix : suffixes) {
            for (var verb : mapping.method())
              implemented.add(verb.name().toLowerCase() + " " + prefix + suffix);
          }
        }
      }
    }
    Set<String> documented = new HashSet<>();
    at(spec, "paths")
        .forEach((path, methods) ->
            object(methods)
                .forEach((verb, operation) -> {
                  documented.add(verb + " " + path);
                  at(operation, "responses")
                      .values()
                      .forEach(response -> {
                        var resolved = resolve(object(response));
                        assertThat(resolved.get("description")).isInstanceOf(String.class);
                        assertThat(resolved.get("description").toString()).isNotBlank();
                      });
                }));
    assertThat(documented).containsExactlyInAnyOrderElementsOf(implemented);
  }

  @Test
  void schemaPropertiesMatchPublicDtos() {
    var schemas = at(spec, "components", "schemas");
    Map.of(
            "WebhookEndpoint",
            EndpointResponse.class,
            "CreatedEndpointResult",
            CreatedEndpointResponse.class,
            "RotateSecretResult",
            RotateSecretResponse.class,
            "EndpointSubscription",
            SubscriptionResponse.class,
            "SubscriptionListResponse",
            SubscriptionListResponse.class,
            "CreateEndpointRequest",
            EndpointCreateRequest.class,
            "UpdateEndpointRequest",
            EndpointUpdateRequest.class)
        .forEach((name, dto) -> propertiesMatch(object(schemas.get(name)), dto));
    propertiesMatch(
        at(
            spec,
            "paths",
            "/v1/endpoints",
            "get",
            "responses",
            "200",
            "content",
            "application/json",
            "schema"),
        EndpointListResponse.class);
    propertiesMatch(
        at(
            spec,
            "paths",
            "/v1/events",
            "post",
            "responses",
            "202",
            "content",
            "application/json",
            "schema"),
        AcceptedEventResponse.class);
  }

  private void propertiesMatch(Map<?, ?> schema, Class<?> dto) {
    var fields =
        Arrays.stream(dto.getRecordComponents()).map(component -> component.getName()).toList();
    assertThat(at(schema, "properties").keySet().stream().map(Object::toString).toList())
        .containsExactlyInAnyOrderElementsOf(fields);
  }

  @Test
  void requestLengthConstraintsMatchBoundaryValidation() throws Exception {
    for (var entry :
        Map.of(
                "CreateEndpointRequest",
                EndpointCreateRequest.class,
                "UpdateEndpointRequest",
                EndpointUpdateRequest.class)
            .entrySet()) {
      for (var field : new String[] {"url", "description"}) {
        var limit = entry.getValue().getDeclaredField(field).getAnnotation(Size.class);
        assertThat(
                at(spec, "components", "schemas", entry.getKey(), "properties", field)
                    .get("maxLength"))
            .isEqualTo(limit.max());
      }
    }
  }

  @Test
  void allReferencesResolveAndSchemasUseOpenApi31Nullability() {
    inspect(spec);
  }

  private void inspect(Object node) {
    if (node instanceof Map<?, ?> map) {
      assertThat(map.containsKey("nullable")).isFalse();
      if (map.containsKey("$ref")) assertThat(resolve(map)).isNotEmpty();
      map.values().forEach(this::inspect);
    } else if (node instanceof List<?> list) list.forEach(this::inspect);
  }

  private Map<?, ?> resolve(Map<?, ?> node) {
    if (!node.containsKey("$ref")) return node;
    String reference = node.get("$ref").toString();
    assertThat(reference).startsWith("#/");
    return at(spec, reference.substring(2).split("/"));
  }
}
