package com.intermanage.backend.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hierarchia sklepu: rangi od najwyższej (poza hierarchią na końcu), ranga osoby, która pyta, i propozycje
 * hierarchii od sklepów zaprzyjaźnionych i do nich.
 */
public record StoreRolesResponse(
        List<StoreRoleResponse> roles,
        /** Ranga pytającego w tym sklepie; {@code null} = właściciel albo brak rangi. */
        UUID myRoleId,
        /** Właściciel albo Aderon: cała hierarchia, przesyłanie i przyjmowanie. */
        boolean admin,
        /** Może zmieniać rangi (STAFF:EDIT): niżej od własnej. */
        boolean canEdit,
        List<Offer> incoming,
        List<Offer> outgoing) {

    public record Offer(UUID id, UUID storeId, String storeNumber, String storeName, List<String> roleNames,
            Instant createdAt, String createdBy) {
    }
}
