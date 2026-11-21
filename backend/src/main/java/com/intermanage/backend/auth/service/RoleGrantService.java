package com.intermanage.backend.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.StoreRole;

/**
 * Kto może zmienić konto i nadać rangę poza właścicielem: hierarchia sklepu ({@link StoreRoleService}). Konto zmienia
 * tylko osoba stojąca wyżej, a rangę nadaje najwyżej równą własnej i bez uprawnień, których sama nie ma. Inaczej
 * osoba zarządzająca pracownikami mogłaby przez stanowisko dać komuś (albo sobie) więcej, niż sama ma.
 */
@Service
public class RoleGrantService {

    private final StoreRoleService ranks;

    public RoleGrantService(StoreRoleService ranks) {
        this.ranks = ranks;
    }

    /** Ranga o kluczu (stanowisko) w sklepie; sklep bez takiej rangi niczego nie nadaje, więc wolno. */
    @Transactional(readOnly = true)
    public boolean canGrantKey(UUID actorId, UUID storeId, String roleKey) {
        StoreRole rank = ranks.activeByKey(storeId, roleKey);
        return rank == null || ranks.canGrant(actorId, rank);
    }

    @Transactional(readOnly = true)
    public void requireGrantableKey(UUID actorId, UUID storeId, String roleKey) {
        StoreRole rank = ranks.activeByKey(storeId, roleKey);
        if (rank != null) {
            ranks.requireGrantable(actorId, rank);
        }
    }

    @Transactional(readOnly = true)
    public boolean canManage(UUID actorId, UUID storeId, AppUser user) {
        return user == null || ranks.canManage(actorId, storeId, user);
    }

    /** Pracownik bez konta nie ma uprawnień, więc zmienić go może każdy z {@code STAFF:EDIT}. */
    @Transactional(readOnly = true)
    public void requireManageable(UUID actorId, UUID storeId, AppUser user) {
        if (user != null) {
            ranks.requireManageable(actorId, storeId, user);
        }
    }

    /** Ranga ze stanowiska w sklepie pracownika (właściciel zostaje właścicielem, bez konta nic). */
    @Transactional
    public void applyPositionRank(AppUser user, UUID storeId, String roleKey, UUID byId) {
        if (user != null && !user.isOwner()) {
            ranks.assignByKey(user, storeId, roleKey, byId);
        }
    }
}
