package com.intermanage.backend.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.intermanage.backend.entity.AppUser;
import com.intermanage.backend.entity.PermissionCategory;
import com.intermanage.backend.entity.PermissionLevel;
import com.intermanage.backend.entity.Store;

/**
 * Zalogowany użytkownik, wczytywany z bazy przy każdym żądaniu na podstawie JWT. Uprawnienia:
 * {@code ROLE_OWNER} dla właściciela oraz {@code KATEGORIA:POZIOM} (np. {@code SALES:VIEW}) dla każdej kategorii,
 * gdzie EDIT daje też VIEW. W kontrolerach: {@code @PreAuthorize("hasAuthority('SALES:EDIT')")}.
 * Dopóki użytkownik nie ustawi własnego hasła, ma wyłącznie {@value #PASSWORD_SETUP_AUTHORITY}.
 */
public final class AppUserPrincipal implements UserDetails, CredentialsContainer {

    public static final String OWNER_AUTHORITY = "ROLE_OWNER";
    public static final String PASSWORD_SETUP_AUTHORITY = "PASSWORD_SETUP_REQUIRED";

    private final UUID id;
    private final String username;
    private String passwordHash;
    private final boolean enabled;
    private final int sessionVersion;
    private final boolean storeAccess;
    private final List<GrantedAuthority> authorities;

    private AppUserPrincipal(UUID id, String username, String passwordHash, boolean enabled, int sessionVersion,
            boolean storeAccess, List<GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.sessionVersion = sessionVersion;
        this.storeAccess = storeAccess;
        this.authorities = authorities;
    }

    /** Wymaga transakcji: dla pracownika sprawdza leniwie wczytywane sklepy. */
    public static AppUserPrincipal from(AppUser user) {
        boolean storeAccess = hasStoreAccess(user);
        if (user.isPasswordChangeRequired()) {
            return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                    user.getSessionVersion(), storeAccess,
                    List.of(new SimpleGrantedAuthority(PASSWORD_SETUP_AUTHORITY)));
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (user.getRole().isOwner()) {
            authorities.add(new SimpleGrantedAuthority(OWNER_AUTHORITY));
        }
        for (Map.Entry<PermissionCategory, PermissionLevel> entry : user.getRole().effectivePermissions().entrySet()) {
            for (PermissionLevel level : PermissionLevel.values()) {
                if (entry.getValue().includes(level)) {
                    authorities.add(new SimpleGrantedAuthority(PermissionLevel.authority(entry.getKey(), level)));
                }
            }
        }
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                user.getSessionVersion(), storeAccess, List.copyOf(authorities));
    }

    /**
     * Pracownik może się zalogować tylko z co najmniej jednym aktywnym sklepem. Właściciel zawsze, bo to on dodaje
     * sklepy i przypisuje je pracownikom.
     */
    public static boolean hasStoreAccess(AppUser user) {
        return user.getRole().isOwner() || user.getStores().stream().anyMatch(Store::isActive);
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
