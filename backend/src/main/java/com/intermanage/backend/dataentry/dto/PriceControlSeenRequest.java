package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Pliki zmian cen widziane w folderze z końcówką .TraiteN (N = liczba przetworzeń przez program sklepu). */
public record PriceControlSeenRequest(@NotNull @Size(max = 500) List<@Valid Seen> files) {

    public record Seen(@NotNull UUID id, @Min(1) int processedCount) {
    }
}
