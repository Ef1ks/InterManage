package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Przedział cen [od, do) z końcówką zaokrąglenia; pusty {@code priceTo} = bez górnej granicy. */
public record PriceRoundingRuleResponse(BigDecimal priceFrom, BigDecimal priceTo, BigDecimal ending, String createdBy,
        Instant createdAt) {
}
