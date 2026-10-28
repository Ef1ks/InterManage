package com.intermanage.backend.deliorder.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Nowe miejsce kategorii w dziale (od 0). */
public record MoveOrderCategoryRequest(@NotNull @Min(0) Integer index) {
}
