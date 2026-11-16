package com.hookrelay.endpoint.api;

import jakarta.validation.constraints.Size;

public record EndpointUpdateRequest(
    @Size(max = 2048, message = "URL must not exceed 2048 characters") String url,
    @Size(max = 160, message = "Description must not exceed 160 characters") String description,
    Boolean enabled) {}
