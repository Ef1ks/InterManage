package com.intermanage.backend.auth.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;
import com.intermanage.backend.auth.entity.Role;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Kto może nadać rolę poza właścicielem: tylko ten, kto sam ma wszystkie jej uprawnienia. Inaczej osoba zarządzająca
 * pracownikami mogłaby przez stanowisko dać komuś (albo sobie) więcej, niż sama ma.
 */
@Service
public class RoleGrantService {

    private final AppUserRepository users;

    public RoleGrantService(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public boolean canGrant(UUID actorId, Role role) {
        Role actor = users.findWithRoleById(actorId).map(AppUser::getRole)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        if (actor.isOwner()) {
            return true;
        }
        if (role.isOwner()) {
            return false;
        }
        Map<PermissionCategory, PermissionLevel> own = actor.effectivePermissions();
        return role.effectivePermissions().entrySet().stream().allMatch(permission -> {
            PermissionLevel level = own.get(permission.getKey());
            return level != null && level.includes(permission.getValue());
        });
    }

    @Transactional(readOnly = true)
    public void requireGrantable(UUID actorId, Role role) {
        if (!canGrant(actorId, role)) {
            throw ApiException.forbidden("Rola „" + role.getName() + "” ma więcej uprawnień niż Twoja. "
                    + "Taką zmianę może zrobić właściciel.");
        }
    }
}
