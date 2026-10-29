package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Plik incydentów: numery sklepów z kolumny Sklep i artykuły (ITM8 dopełniony do 13 cyfr, bez powtórzeń). */
public record ParsedIncidentReport(Set<String> storeNumbers, List<Item> items) {

    public record Item(
            String itm8,
            String ean,
            String name,
            String brand,
            String reason,
            String comment,
            String incidentCode,
            BigDecimal orderedQuantity,
            BigDecimal deliveredQuantity) {
    }
}
