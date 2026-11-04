package com.intermanage.backend.leaflet.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pozycja gazetki odczytana z pliku. {@code meaCode} puste albo {@code DDD} = pozycja do opracowania w sklepie,
 * {@code price} to cena gazetkowa brutto (od -1 do 0 rabat od ceny z kartoteki, -0,2 = -20%).
 */
public record LeafletRow(
        int lineNo,
        String section,
        LocalDate dateFrom,
        LocalDate dateTo,
        String meaCode,
        String itm8,
        String promoItm8,
        String ean,
        String name,
        String brand,
        String promoForm,
        String megaKind,
        String clientPrice,
        BigDecimal price,
        String shelfLabel) {
}
