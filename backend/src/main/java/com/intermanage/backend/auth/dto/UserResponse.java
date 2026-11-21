package com.intermanage.backend.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        /** Rangi w sklepach jednym napisem do listy (właściciel: „Właściciel”). */
        String roleName,
        boolean owner,
        boolean enabled,
        /** Konto założone, ale pracownik jeszcze nie aktywował go linkiem z maila. */
        boolean pendingActivation,
        /** Przypisane sklepy, posortowane po id. */
        List<UUID> storeIds,
        /** Ranga konta w każdym przypisanym sklepie: sklep → ranga. */
        Map<UUID, UUID> storeRoles,
        /** Stanowisko w panelu Pracownicy; to ono ustala rolę konta. */
        String positionName,
        /** Właściciel włączył temu kontu zlecanie zadań (właściciel zleca zawsze). */
        boolean canAssignTasks,
        Instant createdAt) {
}
