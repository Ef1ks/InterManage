package com.intermanage.backend.mea.dto;

import java.time.Instant;

/**
 * Stan kluczy API sklepu dla okna ustawień. Nigdy nie zwraca samego KEY ani SECRET - tylko czy są zapisane.
 * {@code shopId} to numer punktu sprzedaży użyty jako shopID w API.
 */
public record MeaSettingsResponse(
        boolean configured,
        boolean hasSecret,
        Integer shopId,
        Instant updatedAt,
        String updatedBy) {
}
