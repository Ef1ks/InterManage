package com.intermanage.backend.document.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** Pozycje faktury (klucze z wierszy porównania) i artykuły przyjęcia (ITM8), które są tym samym towarem. */
public record InvoiceComparisonMatchRequest(
        @NotEmpty(message = "Zaznacz pozycję z faktury") List<@NotBlank String> invoiceKeys,
        @NotEmpty(message = "Zaznacz artykuł z przyjęcia") List<@NotBlank String> itm8s) {
}
