package com.intermanage.backend.deliproduction.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Schemat parametrów procesu; puste pole = na karcie "-". */
public record ProductionSchemaRequest(
        @NotBlank(message = "Wpisz nazwę schematu") @Size(max = 100, message = "Najwyżej 100 znaków") String name,
        @Min(value = 0, message = "Od 0") @Max(value = 400, message = "Najwyżej 400") Integer bakingTemperature,
        @Min(value = 0, message = "Od 0") @Max(value = 200, message = "Najwyżej 200") Integer internalTemperature,
        @Min(value = 0, message = "Od 0") @Max(value = 1440, message = "Najwyżej 1440") Integer coolingTime,
        @Min(value = -40, message = "Od -40") @Max(value = 100, message = "Najwyżej 100") Integer coolingTemperature,
        @Min(value = 0, message = "Od 0") @Max(value = 365, message = "Najwyżej 365") Integer shelfLifeDays) {
}
