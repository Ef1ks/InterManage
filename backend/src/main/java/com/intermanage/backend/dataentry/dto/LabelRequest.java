package com.intermanage.backend.dataentry.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Artykuły tabeli do pliku etykiet: EAN i liczba etykiet. */
public record LabelRequest(
        @NotNull @Size(min = 1, max = 2000, message = "Od 1 do 2000 pozycji") List<@Valid Line> lines) {

    public record Line(
            @NotBlank @Size(max = 14) String ean,
            @Min(value = 1, message = "Co najmniej 1 etykieta")
            @Max(value = 9999, message = "Najwyżej 9999 etykiet jednego artykułu") int count) {
    }
}
