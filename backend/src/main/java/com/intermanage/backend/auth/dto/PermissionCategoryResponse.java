package com.intermanage.backend.auth.dto;

import com.intermanage.backend.auth.entity.PermissionCategory;

public record PermissionCategoryResponse(PermissionCategory code, String label, String description) {
}
