package com.intermanage.backend.deliproduction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Karta produkcji z pozycjami. {@code editable} = karta bieżącego sklepu (karty sklepów zaprzyjaźnionych są tylko do
 * podglądu i wydruku).
 */
public record ProductionCardResponse(
        UUID id,
        UUID storeId,
        String storeNumber,
        String storeName,
        LocalDate productionDate,
        List<Line> lines,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        Instant deletedAt,
        boolean editable) {

    public record Line(
            UUID id,
            UUID articleId,
            String code,
            String name,
            BigDecimal quantity,
            int startHour,
            int endHour,
            Integer bakingTemperature,
            Integer internalTemperature,
            Integer coolingTime,
            Integer coolingTemperature,
            Integer shelfLifeDays,
            LocalDate expiryDate) {
    }
}
