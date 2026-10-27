package com.intermanage.backend.dto;

import java.time.Instant;
import java.util.UUID;

public record StoreResponse(
        UUID id,
        String number,
        String name,
        String address,
        String nip,
        boolean active,
        /** Ilu pracowników ma przypisany sklep (właściciele widzą wszystkie sklepy i nie są liczeni). */
        long userCount,
        Instant createdAt) {
}
