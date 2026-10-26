package com.intermanage.backend.dto;

/** Wiersz raportu "Lista dostawców"; {@code name} to Nazwa firmy 1 i 2 razem, {@code address} - Adres 1 i 2. */
public record SupplierReportRow(
        String code,
        String keyword,
        String name,
        String nip,
        String address,
        String postalCode,
        String city,
        String origin) {
}
