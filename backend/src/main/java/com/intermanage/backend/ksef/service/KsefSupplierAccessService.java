package com.intermanage.backend.ksef.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.ksef.dto.KsefSupplierAccessResponse;
import com.intermanage.backend.ksef.entity.KsefAllSuppliersAccess;
import com.intermanage.backend.ksef.repository.KsefAllSuppliersAccessRepository;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.task.repository.StoreTaskRepository;

/**
 * Kto w Fakturach KSeF widzi faktury wszystkich dostawców. Domyślnie konto widzi tylko dostawców z listy dostawców
 * sklepu (wgranej z raportu); wszystkich widzą właściciel, Aderon i ranga Księgowość, a pozostałym kontom włącza to
 * Aderon (albo właściciel) osobno w każdym sklepie.
 */
@Service
public class KsefSupplierAccessService {

    /** Klucz rangi Księgowość ({@code StoreRoleDefaults}). */
    public static final String ACCOUNTING_RANK = "ACCOUNTING";

    private final KsefAllSuppliersAccessRepository access;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final StoreTaskRepository tasks;

    public KsefSupplierAccessService(KsefAllSuppliersAccessRepository access, AppUserRepository users,
            StoreRepository stores, StoreTaskRepository tasks) {
        this.access = access;
        this.users = users;
        this.stores = stores;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public boolean seesAllSuppliers(UUID storeId, UUID userId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        return reason(user, storeId, false) != null || access.findActive(storeId, userId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<KsefSupplierAccessResponse> list(UUID storeId) {
        Set<UUID> granted = new HashSet<>(access.findActiveUserIds(storeId));
        return tasks.findAssignees(storeId).stream().map(user -> response(user, storeId, granted)).toList();
    }

    @Transactional
    public List<KsefSupplierAccessResponse> set(UUID storeId, UUID userId, boolean allSuppliers, UUID actorId) {
        AppUser user = tasks.findAssignees(storeId).stream().filter(candidate -> candidate.getId().equals(userId))
                .findFirst().orElseThrow(() -> ApiException.notFound("To konto nie ma dostępu do sklepu"));
        if (reason(user, storeId, false) != null) {
            throw ApiException.badRequest("To konto widzi wszystkich dostawców z urzędu");
        }
        AppUser actor = users.getReferenceById(actorId);
        var current = access.findActive(storeId, userId);
        if (allSuppliers && current.isEmpty()) {
            access.save(new KsefAllSuppliersAccess(stores.getReferenceById(storeId), user, actor));
        } else if (!allSuppliers) {
            current.ifPresent(entry -> entry.markRemoved(actor));
        }
        access.flush();
        return list(storeId);
    }

    private KsefSupplierAccessResponse response(AppUser user, UUID storeId, Set<UUID> granted) {
        String reason = reason(user, storeId, granted.contains(user.getId()));
        return new KsefSupplierAccessResponse(user.getId(), user.getDisplayName(), user.rankNameIn(storeId),
                reason == null ? "NONE" : reason, reason != null);
    }

    /** Powód widzenia wszystkich dostawców, {@code null} = tylko dostawcy z listy. */
    private static String reason(AppUser user, UUID storeId, boolean granted) {
        if (user.isOwner()) {
            return "OWNER";
        }
        StoreRole rank = user.rankIn(storeId);
        if (rank != null && rank.isAdmin()) {
            return "ADERON";
        }
        if (rank != null && ACCOUNTING_RANK.equals(rank.getKey())) {
            return "ACCOUNTING";
        }
        return granted ? "GRANTED" : null;
    }
}
