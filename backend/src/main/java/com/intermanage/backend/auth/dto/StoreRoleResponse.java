package com.intermanage.backend.auth.dto;

import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;

/**
 * Ranga sklepu. {@code editable}: osoba, która pyta, może zmienić nazwę i uprawnienia (ranga niżej od jej własnej,
 * albo właściciel i Aderon), {@code deletable}: może ją usunąć, {@code grantable}: może ją komuś nadać.
 */
public record StoreRoleResponse(
        UUID id,
        String key,
        String name,
        String description,
        boolean admin,
        boolean outsideHierarchy,
        int position,
        Map<PermissionCategory, PermissionLevel> permissions,
        long userCount,
        boolean editable,
        boolean deletable,
        boolean grantable) {
}
