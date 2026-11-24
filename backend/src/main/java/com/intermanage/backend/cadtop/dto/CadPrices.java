package com.intermanage.backend.cadtop.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Ceny artykułów z obowiązującego cadencier (plik TOP), po ITM8 artykułu ze sklepu. {@code nrCad} i {@code from}: numer
 * i dzień jego wejścia; puste, gdy żaden plik TOP dziś nie obowiązuje.
 */
public record CadPrices(String nrCad, LocalDate from, Map<String, Price> byItm8) {

    public static final CadPrices NONE = new CadPrices(null, null, Map.of());

    /** Cena zakupu netto i sprzedaży brutto sztuki. */
    public record Price(String nrCad, LocalDate from, BigDecimal purchasePrice, BigDecimal salePrice) {
    }

    public Price of(String itm8) {
        return byItm8.get(itm8);
    }
}
