package com.intermanage.backend.gus.dto;

import java.time.LocalDate;

/**
 * Firma z rejestru REGON (GUS) po NIP-ie. {@code street} to ulica z numerem (w miejscowości bez ulic sama miejscowość
 * z numerem), {@code address} cały adres w jednej linii dla formularzy z jednym polem. {@code endedAt}: firma
 * zakończyła działalność.
 */
public record GusCompany(
        String nip,
        String regon,
        String name,
        String street,
        String postalCode,
        String city,
        String address,
        LocalDate endedAt) {
}
