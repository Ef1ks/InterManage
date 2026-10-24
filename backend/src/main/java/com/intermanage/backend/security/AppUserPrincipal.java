package com.intermanage.backend.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.intermanage.backend.permission.PermissionCategory;
import com.intermanage.backend.permission.PermissionLevel;
import com.intermanage.backend.user.AppUser;

/**
 * Zalogowany użytkownik, wczytywany z bazy przy każdym żądaniu na podstawie JWT. Uprawnienia:
 * {@code ROLE_OWNER} dla właściciela oraz {@code KATEGORIA:POZIOM} (np. {@code SALES:VIEW}) dla każdej kategorii,
 * gdzie EDIT daje też VIEW. W kontrolerach: {@code @PreAuthorize("hasAuthority('SALES:EDIT')")}.
 * Dopóki użytkownik nie ustawi własnego hasła, ma wyłącznie {@value #PASSWORD_SETUP_AUTHORITY}.
 */
public final class AppUserPrincipal implements UserDetails, CredentialsContainer {

    public static final String OWNER_AUTHORITY = "ROLE_OWNER";
    public static final String PASSWORD_SETUP_AUTHORITY = "PASSWORD_SETUP_REQUIRED";

    private final Long id;
    private final String username;
    private String passwordHash;
    private final boolean enabled;
    private final int sessionVersion;
    private final List<GrantedAuthority> authorities;

    private AppUserPrincipal(Long id, String username, String passwordHash, boolean enabled, int sessionVersion,
            List<GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.sessionVersion = sessionVersion;
        this.authorities = authorities;
    }

    public static AppUserPrincipal from(AppUser user) {
        if (user.isPasswordChangeRequired()) {
            return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled(),
                    user.getSessionVersion(), List.of(new SimpleGrantedAuthority(PASSWORD_SETUP_AUTHORITY)));
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
                user.getSessionVersion(), List.copyOf(authorities));
    }

    public Long getId() {
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
