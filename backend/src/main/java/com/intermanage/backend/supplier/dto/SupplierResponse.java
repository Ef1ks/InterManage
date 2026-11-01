package com.intermanage.backend.supplier.dto;

import java.util.UUID;

/**
 * Dostawca sklepu; {@code manual}, gdy dodany w aplikacji (wtedy bez kodu z systemu sklepu). Z kartoteki dostawców:
 * strona, telefon, e-mail, liczba kontaktów i pierwszy przedstawiciel ({@code null}, gdy go nie ma).
 */
public record SupplierResponse(
        UUID id,
        String code,
        String keyword,
        String name,
        String nip,
        String city,
        boolean manual,
        String website,
        String phone,
        String email,
        int contactCount,
        SupplierContactResponse representative) {
}
