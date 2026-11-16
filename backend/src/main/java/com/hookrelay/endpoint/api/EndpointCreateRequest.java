package com.hookrelay.endpoint.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EndpointCreateRequest(
    @NotBlank(message = "URL is required")
        @Size(max = 2048, message = "URL must not exceed 2048 characters")
        @Pattern(regexp = "^https://.+", message = "URL must use the HTTPS scheme")
        String url,
    @Size(max = 160, message = "Description must not exceed 160 characters") String description) {}
