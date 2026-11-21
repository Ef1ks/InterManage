package com.intermanage.backend.auth.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.store.entity.Store;

/**
 * Zalogowany użytkownik, wczytywany z bazy przy każdym żądaniu na podstawie JWT. Uprawnienia:
 * {@code ROLE_OWNER} dla właściciela oraz {@code KATEGORIA:POZIOM} (np. {@code SALES:VIEW}) dla każdej kategorii,
 * gdzie EDIT daje też VIEW. W kontrolerach: {@code @PreAuthorize("hasAuthority('SALES:EDIT')")}.
 * Dopóki użytkownik nie ustawi własnego hasła, ma wyłącznie {@value #PASSWORD_SETUP_AUTHORITY}.
 */
public final class AppUserPrincipal implements UserDetails, CredentialsContainer {

    public static final String OWNER_AUTHORITY = "ROLE_OWNER";
    /** Aderon w sklepie z adresu żądania: wszystko w obrębie sklepu, ale nie ustawienia firmy. */
    public static final String STORE_ADMIN_AUTHORITY = "STORE:ADMIN";
    /** Zlecanie zadań (właściciel, Aderon, ranga z kimś poniżej albo konto z włączonym zlecaniem). */
    public static final String TASK_ASSIGN_AUTHORITY = "TASKS:ASSIGN";
    public static final String PASSWORD_SETUP_AUTHORITY = "PASSWORD_SETUP_REQUIRED";

    private final UUID id;
    private final String username;
    private String passwordHash;
    private final boolean enabled;
    private final int sessionVersion;
    private final boolean storeAccess;
    /** Uprawnienia niezależne od sklepu (właściciel, ustawienie hasła). */
    private final List<GrantedAuthority> global;
    /** Uprawnienia z rangi konta w każdym sklepie. */
    private final Map<UUID, List<GrantedAuthority>> perStore;
    private final List<GrantedAuthority> authorities;

    private AppUserPrincipal(UUID id, String username, String passwordHash, boolean enabled, int sessionVersion,
            boolean storeAccess, List<GrantedAuthority> global, Map<UUID, List<GrantedAuthority>> perStore,
            List<GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.sessionVersion = sessionVersion;
        this.storeAccess = storeAccess;
        this.global = global;
        this.perStore = perStore;
        this.authorities = authorities;
    }

    /**
     * Wymaga transakcji: dla pracownika sprawdza leniwie wczytywane sklepy i rangi. Bez wskazania sklepu
     * ({@link #forStore}) uprawnienia to suma uprawnień ze wszystkich sklepów konta.
     */
    public static AppUserPrincipal from(AppUser user) {
        boolean storeAccess = hasStoreAccess(user);
        if (user.isPasswordChangeRequired()) {
            List<GrantedAuthority> setup = List.of(new SimpleGrantedAuthority(PASSWORD_SETUP_AUTHORITY));
            return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                    user.getSessionVersion(), storeAccess, setup, Map.of(), setup);
        }
        List<GrantedAuthority> global = new ArrayList<>();
        Map<UUID, List<GrantedAuthority>> perStore = new HashMap<>();
        if (user.isOwner()) {
            global.add(new SimpleGrantedAuthority(OWNER_AUTHORITY));
            global.add(new SimpleGrantedAuthority(TASK_ASSIGN_AUTHORITY));
            global.addAll(categories(user.getRole().effectivePermissions()));
        } else {
            for (Store store : user.getStores()) {
                List<GrantedAuthority> own = new ArrayList<>();
                StoreRole rank = user.rankIn(store.getId());
                if (rank != null) {
                    own.addAll(categories(rank.effectivePermissions()));
                    if (rank.isAdmin()) {
                        own.add(new SimpleGrantedAuthority(STORE_ADMIN_AUTHORITY));
                    }
                }
                boolean hasSubordinates = rank != null
                        && (rank.isAdmin() || (!rank.isOutsideHierarchy() && rank.getLowerRanks() > 0));
                if (user.isCanAssignTasks() || hasSubordinates) {
                    own.add(new SimpleGrantedAuthority(TASK_ASSIGN_AUTHORITY));
                }
                perStore.put(store.getId(), List.copyOf(own));
            }
        }
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                user.getSessionVersion(), storeAccess, List.copyOf(global), Map.copyOf(perStore),
                merge(global, perStore.values()));
    }

    /**
     * Uprawnienia w sklepie z adresu żądania ({@code /api/stores/{id}/...}): ranga konta w tym sklepie. Sklep, do
     * którego konto nie ma rangi, nie daje nic poza uprawnieniami niezależnymi od sklepu.
     */
    public AppUserPrincipal forStore(UUID storeId) {
        if (storeId == null) {
            return this;
        }
        List<GrantedAuthority> store = perStore.getOrDefault(storeId, List.of());
        return new AppUserPrincipal(id, username, passwordHash, enabled, sessionVersion, storeAccess, global,
                perStore, merge(global, List.of(store)));
    }

    /** Właściciel albo Aderon sklepu z adresu żądania. */
    public boolean administers() {
        return has(OWNER_AUTHORITY) || has(STORE_ADMIN_AUTHORITY);
    }

    public boolean isOwner() {
        return has(OWNER_AUTHORITY);
    }

    public boolean has(String authority) {
        return authorities.stream().anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private static List<GrantedAuthority> categories(Map<PermissionCategory, PermissionLevel> permissions) {
        List<GrantedAuthority> result = new ArrayList<>();
        for (Map.Entry<PermissionCategory, PermissionLevel> entry : permissions.entrySet()) {
            for (PermissionLevel level : PermissionLevel.values()) {
                if (entry.getValue().includes(level)) {
                    result.add(new SimpleGrantedAuthority(PermissionLevel.authority(entry.getKey(), level)));
                }
            }
        }
        return result;
    }

    private static List<GrantedAuthority> merge(List<GrantedAuthority> global,
            Collection<List<GrantedAuthority>> stores) {
        Set<String> names = new LinkedHashSet<>();
        global.forEach(authority -> names.add(authority.getAuthority()));
        stores.forEach(list -> list.forEach(authority -> names.add(authority.getAuthority())));
        return names.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    /**
     * Pracownik może się zalogować tylko z co najmniej jednym aktywnym sklepem. Właściciel zawsze, bo to on dodaje
     * sklepy i przypisuje je pracownikom.
     */
    public static boolean hasStoreAccess(AppUser user) {
        return user.isOwner() || user.getStores().stream().anyMatch(Store::isActive);
    }

    public boolean hasStoreAccess() {
        return storeAccess;
    }

    public UUID getId() {
        return id;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
