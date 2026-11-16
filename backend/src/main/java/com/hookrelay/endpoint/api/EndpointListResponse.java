package com.hookrelay.endpoint.api;

import java.util.List;

public record EndpointListResponse(
    List<EndpointResponse> endpoints, int page, int size, int total) {}
