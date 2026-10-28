package com.intermanage.backend.deliorder.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Nowe miejsce produktu formularza: kategoria (ta sama albo inna w tym sklepie) i miejsce w niej (od 0). */
public record MoveOrderProductRequest(@NotNull UUID categoryId, @NotNull @Min(0) Integer index) {
}
