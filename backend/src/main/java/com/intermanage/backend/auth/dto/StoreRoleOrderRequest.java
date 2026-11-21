package com.intermanage.backend.auth.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Rangi w hierarchii od najwyższej, bez Aderona (stoi zawsze na górze) i bez rang poza hierarchią. */
public record StoreRoleOrderRequest(@NotNull(message = "Podaj kolejność rang") List<UUID> roleIds) {
}
