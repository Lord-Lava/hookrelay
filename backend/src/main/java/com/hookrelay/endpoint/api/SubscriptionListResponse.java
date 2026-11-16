package com.hookrelay.endpoint.api;

import java.util.List;

public record SubscriptionListResponse(
    List<SubscriptionResponse> subscriptions, int page, int size, int total) {}
