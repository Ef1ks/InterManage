package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code storeId}: sklep firmy, w którym pracuje teraz. {@code endedAt}: koniec pracy (da się cofnąć).
 * {@code ownerAccount}: stanowisko nie zmienia roli właściciela. {@code hasAccount} fałsz = pracownik bez konta
 * (tylko imię i nazwisko, bez loginu i e-maila; {@code userId}, {@code username}, {@code email} puste).
 */
public record EmployeeResponse(UUID id, UUID userId, boolean hasAccount, String displayName, String username,
        String email,
        boolean enabled, boolean pendingActivation, boolean ownerAccount, String roleName, UUID storeId,
        String storeNumber, String storeName, UUID departmentId,
        String departmentName, UUID positionId, String positionName, List<ShiftResponse> shifts, Instant createdAt,
        Instant endedAt, List<UUID> extraPositionIds) {
}
