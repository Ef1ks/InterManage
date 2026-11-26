package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Odczytana lista opakowań zwrotnych. */
public record ParsedDepositKindReport(String storeNumber, LocalDateTime generatedAt, List<Row> rows) {

    public record Row(short code, String name, Short vatCode, BigDecimal value, String kind) {
    }
}
