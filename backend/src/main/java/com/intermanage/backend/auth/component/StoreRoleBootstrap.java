package com.intermanage.backend.auth.component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.Role;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.RoleRepository;
import com.intermanage.backend.auth.service.StoreRoleService;
import com.intermanage.backend.staff.repository.StaffPositionRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Przy starcie, zanim serwer przyjmie żądania: każdy sklep bez rang dostaje domyślne, a konta z dawną rolą
 * globalną dostają w swoich sklepach rangę z tej roli (jej uprawnienia przechodzą na rangę), stanowiska klucz rangi,
 * a konto rolę „Konto sklepu”. Po pierwszym razie nic już nie robi (poza sklepem bez rang).
 */
@Component
public class StoreRoleBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StoreRoleBootstrap.class);

    private final StoreRepository stores;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final StaffPositionRepository positions;
    private final StoreRoleService service;
    private final TransactionTemplate tx;

    public StoreRoleBootstrap(StoreRepository stores, RoleRepository roles, AppUserRepository users,
            StaffPositionRepository positions, StoreRoleService service, PlatformTransactionManager transactions) {
        this.stores = stores;
        this.roles = roles;
        this.users = users;
        this.positions = positions;
        this.service = service;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void afterSingletonsInstantiated() {
        tx.executeWithoutResult(status -> run());
    }

    private void run() {
        List<AppUser> legacyUsers = users.findWithLegacyRole();
        List<Role> legacyRoles = roles.findAllWithPermissions().stream()
                .filter(role -> !role.isOwner() && role.getSystemKey() == null).toList();
        boolean migrate = !legacyUsers.isEmpty();
        for (Store store : stores.findAll()) {
            if (migrate) {
                Map<UUID, StoreRole> byRole = service.adoptLegacyRoles(store, legacyRoles);
                for (AppUser user : legacyUsers) {
                    StoreRole rank = byRole.get(user.getRole().getId());
                    if (rank != null && user.getStores().stream().anyMatch(own -> own.getId().equals(store.getId()))) {
                        service.assign(user, rank, null);
                    }
                }
            } else {
                service.ensureDefaults(store, null);
            }
        }
        if (!migrate) {
            return;
        }
        for (Role role : legacyRoles) {
            positions.adoptRoleKey(role.getId(), StoreRoleService.legacyKey(role));
        }
        Role account = roles.findBySystemKey(Role.ACCOUNT_KEY)
                .orElseThrow(() -> new IllegalStateException("Brak roli Konto sklepu - sprawdź migracje Flyway"));
        legacyUsers.forEach(user -> user.setRole(account));
        log.info("Hierarchia sklepów: przeniesiono {} kont i {} dawnych ról do rang sklepów", legacyUsers.size(),
                legacyRoles.size());
    }
}
