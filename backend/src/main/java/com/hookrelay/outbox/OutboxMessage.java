package com.hookrelay.outbox;

import java.util.UUID;

public record OutboxMessage(UUID id, String payloadJson, int publishAttempts) {}
