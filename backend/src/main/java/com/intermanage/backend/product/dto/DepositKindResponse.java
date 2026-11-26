package com.intermanage.backend.product.dto;

import java.math.BigDecimal;

/** Rodzaj opakowania zwrotnego: kod kaucji, nazwa i wartość. */
public record DepositKindResponse(short code, String name, Short vatCode, BigDecimal value) {
}
