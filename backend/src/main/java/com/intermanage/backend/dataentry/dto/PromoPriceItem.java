package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Cena zwykła artykułu w promocji w kolejce do makra; {@code generatedAt}: poszła już do makra. */
public record PromoPriceItem(UUID id, String ean, String itm8, String name, BigDecimal salePrice, String vatCode,
        BigDecimal promoPrice, LocalDate promoTo, String document, Instant createdAt, Instant generatedAt) {
}
