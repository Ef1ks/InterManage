package com.intermanage.backend.dto;

import com.intermanage.backend.entity.PermissionCategory;

public record PermissionCategoryResponse(PermissionCategory code, String label, String description) {
}
