package com.intermanage.backend.auth.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;
import com.intermanage.backend.store.dto.StoreSummary;

public record MeResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        String roleName,
        boolean owner,
        Map<PermissionCategory, PermissionLevel> permissions,
        /** Frontend wpuszcza tylko na ekran ustawienia hasła, backend blokuje resztę API. */
        boolean passwordChangeRequired,
        /** Sklepy do wyboru w przełączniku: właściciel wszystkie aktywne, pracownik przypisane aktywne. */
        List<StoreSummary> stores) {
}
