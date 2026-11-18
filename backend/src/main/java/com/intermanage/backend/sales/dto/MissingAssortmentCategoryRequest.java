package com.intermanage.backend.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nazwa kategorii brakującego asortymentu, np. „wycofane z produkcji”. */
public record MissingAssortmentCategoryRequest(@NotBlank(message = "Podaj nazwę") @Size(max = 60) String name) {
}
