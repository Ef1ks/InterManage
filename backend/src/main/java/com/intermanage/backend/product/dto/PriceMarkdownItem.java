package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.product.entity.PriceLabelKind;

/**
 * Wpis na liście cenówek: przecena albo zmiana ceny ({@code kind}, przy zmianie {@code validTo} pusty);
 * {@code labelsGeneratedAt}: artykuł był już w pliku ETIQUETTE.
 */
public record PriceMarkdownItem(UUID id, PriceLabelKind kind, String itm8, String ean, String name,
        BigDecimal regularPrice, BigDecimal newPrice, LocalDate validFrom, LocalDate validTo, Instant createdAt,
        Instant labelsGeneratedAt) {
}
