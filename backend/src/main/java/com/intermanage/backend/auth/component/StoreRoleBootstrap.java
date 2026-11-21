package com.intermanage.backend.auth.component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * Rangą w sklepie staje się tylko dawna rola, która jest tam potrzebna: rola konta z dostępem do sklepu albo rola
 * stanowiska (stanowiska są wspólne dla firmy, więc ich role trafiają do każdego sklepu). Rola bez kont w sklepie
 * nie zakłada w nim rangi; wcześniej każda rola trafiała do każdego sklepu, co przy wielu rolach i sklepach (baza
 * testów) dawało setki tysięcy rang przy każdym starcie.
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
        Set<UUID> positionRoles = migrate ? new HashSet<>(positions.findRoleIdsWithoutKey()) : Set.of();
        for (Store store : stores.findAll()) {
            if (!migrate) {
                service.ensureDefaults(store, null);
                continue;
            }
            List<AppUser> storeUsers = legacyUsers.stream()
                    .filter(user -> user.getStores().stream().anyMatch(own -> own.getId().equals(store.getId())))
                    .toList();
            Set<UUID> needed = new HashSet<>(positionRoles);
            storeUsers.forEach(user -> needed.add(user.getRole().getId()));
            List<Role> storeRoles = legacyRoles.stream().filter(role -> needed.contains(role.getId())).toList();
            if (storeRoles.isEmpty()) {
                service.ensureDefaults(store, null);
                continue;
            }
            Map<UUID, StoreRole> byRole = service.adoptLegacyRoles(store, storeRoles);
            for (AppUser user : storeUsers) {
                StoreRole rank = byRole.get(user.getRole().getId());
                if (rank != null) {
                    service.assign(user, rank, null);
                }
            }
        }
        if (!migrate) {
            return;
        }
        for (Role role : legacyRoles) {
            if (positionRoles.contains(role.getId())) {
                positions.adoptRoleKey(role.getId(), StoreRoleService.legacyKey(role));
            }
        }
        Role account = roles.findBySystemKey(Role.ACCOUNT_KEY)
                .orElseThrow(() -> new IllegalStateException("Brak roli Konto sklepu - sprawdź migracje Flyway"));
        legacyUsers.forEach(user -> user.setRole(account));
        log.info("Hierarchia sklepów: przeniesiono {} kont i {} dawnych ról do rang sklepów", legacyUsers.size(),
                legacyRoles.size());
    }
}
