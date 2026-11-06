package com.intermanage.backend.deliprice.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Przypisanie wyrobu sąsiada {@code sourceProductId} do artykułu własnej kartoteki o ITM8 {@code itm8}. */
public record CalculationLinkRequest(@NotNull UUID sourceProductId, @NotBlank String itm8) {
}
