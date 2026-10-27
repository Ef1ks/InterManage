package com.intermanage.backend.auth.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Sprawdza dostęp do sklepu w {@code @PreAuthorize}, np.
 * {@code @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")}.
 * Właściciel ma dostęp do każdego istniejącego sklepu (także wyłączonego), pracownik tylko do przypisanych aktywnych.
 */
@Component("storeAccess")
public class StoreAccess {

    private final StoreRepository stores;

    public StoreAccess(StoreRepository stores) {
        this.stores = stores;
    }

    public boolean canAccess(Authentication authentication, UUID storeId) {
        if (storeId == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return false;
        }
        boolean owner = principal.getAuthorities().stream()
                .anyMatch(authority -> AppUserPrincipal.OWNER_AUTHORITY.equals(authority.getAuthority()));
        return owner ? stores.existsById(storeId) : stores.isAssigned(principal.getId(), storeId);
    }
}
