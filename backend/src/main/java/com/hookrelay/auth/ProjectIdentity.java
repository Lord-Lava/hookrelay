package com.hookrelay.auth;

import java.util.UUID;

public record ProjectIdentity(UUID organizationId, UUID projectId, UUID apiKeyId) {}
