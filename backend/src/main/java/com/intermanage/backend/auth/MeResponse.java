package com.intermanage.backend.auth;

import java.util.Map;

import com.intermanage.backend.permission.PermissionCategory;
import com.intermanage.backend.permission.PermissionLevel;
import com.intermanage.backend.user.AppUser;

public record MeResponse(
        Long id,
        String username,
        String displayName,
        String email,
        String roleName,
        boolean owner,
        Map<PermissionCategory, PermissionLevel> permissions,
        /** Frontend wpuszcza tylko na ekran ustawienia hasła, backend blokuje resztę API. */
        boolean passwordChangeRequired) {

    public static MeResponse from(AppUser user) {
        return new MeResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole().getName(), user.getRole().isOwner(), user.getRole().effectivePermissions(),
                user.isPasswordChangeRequired());
    }
}
