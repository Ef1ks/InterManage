package com.intermanage.backend.supplier.dto;

import java.util.UUID;

/** Dostawca sklepu; {@code manual}, gdy dodany w aplikacji (wtedy bez kodu z systemu sklepu). */
public record SupplierResponse(
        UUID id,
        String code,
        String keyword,
        String name,
        String nip,
        String city,
        boolean manual) {
}
