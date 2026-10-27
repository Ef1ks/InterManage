package com.intermanage.backend.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        UUID roleId,
        String roleName,
        boolean owner,
        boolean enabled,
        /** Konto założone, ale pracownik jeszcze nie aktywował go linkiem z maila. */
        boolean pendingActivation,
        /** Przypisane sklepy, posortowane po id. */
        List<UUID> storeIds,
        Instant createdAt) {
}
