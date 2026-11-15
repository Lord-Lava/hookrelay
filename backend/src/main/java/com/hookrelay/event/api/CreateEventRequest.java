package com.hookrelay.event.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record CreateEventRequest(
    @NotBlank
        @Size(max = 128)
        @Pattern(
            regexp = "[a-z0-9]+([._-][a-z0-9]+)*",
            message =
                "must be lowercase and contain only letters, numbers, dots, underscores or hyphens")
        String eventType,
    @NotNull JsonNode data) {

  @AssertTrue(message = "must be a JSON object")
  @JsonIgnore
  public boolean isDataObject() {
    return data != null && data.isObject();
  }
}
