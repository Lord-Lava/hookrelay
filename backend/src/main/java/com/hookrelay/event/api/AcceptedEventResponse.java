package com.hookrelay.event.api;

import java.time.Instant;
import java.util.UUID;

public record AcceptedEventResponse(UUID eventId, Instant createdAt, String status) {}
