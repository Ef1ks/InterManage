package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Artykuły sprawdzone pod kątem za niskiej marży, z cenami, które widział sprawdzający (weryfikacja obowiązuje tylko
 * dla nich).
 */
public record ArticleMarginVerificationRequest(
        @NotEmpty(message = "Wybierz artykuły") @Size(max = 5000, message = "Najwyżej 5000 artykułów naraz")
        List<@Valid @NotNull Entry> items,
        @Size(max = 500, message = "Najwyżej 500 znaków") String note) {

    public record Entry(
            @NotBlank @Size(max = 13) String itm8,
            @NotNull BigDecimal salePrice,
            @NotNull BigDecimal purchasePrice,
            @NotNull BigDecimal marginPercent,
            /** Data ostatniego przyjęcia PZ, którą widział sprawdzający (pusta = cena z kartoteki). */
            java.time.LocalDateTime lastPurchaseDate) {
    }
}
