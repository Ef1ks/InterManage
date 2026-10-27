package com.intermanage.backend.auth.dto;

import java.util.Map;

import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoleRequest(
        @NotBlank(message = "Podaj nazwę roli")
        @Size(max = 100, message = "Nazwa może mieć maksymalnie 100 znaków") String name,
        @Size(max = 500, message = "Opis może mieć maksymalnie 500 znaków") String description,
        @NotNull(message = "Podaj uprawnienia") Map<PermissionCategory, PermissionLevel> permissions) {
}
