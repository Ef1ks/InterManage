package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Przecena na liście cenówek; {@code labelsGeneratedAt}: artykuł był już w pliku ETIQUETTE. */
public record PriceMarkdownItem(UUID id, String itm8, String ean, String name, BigDecimal regularPrice,
        BigDecimal newPrice, LocalDate validFrom, LocalDate validTo, Instant createdAt, Instant labelsGeneratedAt) {
}
