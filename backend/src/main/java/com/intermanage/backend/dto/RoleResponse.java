package com.intermanage.backend.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.entity.PermissionCategory;
import com.intermanage.backend.entity.PermissionLevel;

public record RoleResponse(
        UUID id,
        String name,
        String description,
        boolean owner,
        Map<PermissionCategory, PermissionLevel> permissions,
        long userCount,
        Instant createdAt) {
}
