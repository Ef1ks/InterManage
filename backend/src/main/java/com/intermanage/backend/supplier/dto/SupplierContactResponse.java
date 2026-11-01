package com.intermanage.backend.supplier.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.supplier.entity.SupplierContactKind;

/** Kontakt u dostawcy; {@code updatedAt}/{@code updatedBy} puste, dopóki nikt go nie zmienił. */
public record SupplierContactResponse(
        UUID id,
        SupplierContactKind kind,
        String name,
        String jobTitle,
        String phone,
        String email,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy) {
}
