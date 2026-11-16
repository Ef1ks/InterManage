package com.intermanage.backend.mea.dto;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Stan odczytów dziennika ATHOS w sklepie: {@code read} = sklep odczytał go choć raz (wtedy zakładki liczą się
 * z wysyłek), ostatni odczyt (kiedy, kto, plik, data jego zmiany, wysyłki w pliku i nowe), liczba zapisanych wysyłek
 * i najnowsza wysyłka.
 */
public record MeaAthosStatus(
        boolean read,
        Instant importedAt,
        String importedBy,
        String fileName,
        Instant fileModified,
        int foundCount,
        int addedCount,
        long sendCount,
        LocalDateTime lastSentAt) {
}
