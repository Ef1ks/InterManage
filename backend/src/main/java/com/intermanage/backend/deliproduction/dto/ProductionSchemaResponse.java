package com.intermanage.backend.deliproduction.dto;

import java.util.UUID;

/** Schemat z liczbą artykułów, które go wybrały. */
public record ProductionSchemaResponse(
        UUID id,
        String name,
        Integer bakingTemperature,
        Integer internalTemperature,
        Integer coolingTime,
        Integer coolingTemperature,
        Integer shelfLifeDays,
        long articleCount) {
}
