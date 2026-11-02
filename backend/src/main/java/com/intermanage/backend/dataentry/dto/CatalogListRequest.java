package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

/**
 * Filtry listy towarów z parametrów zapytania. Teksty to fragmenty z {@code *} (dowolny ciąg) i {@code ?} (jeden znak);
 * {@code supplier}: kod albo fragment nazwy dostawcy z przyjęć PZ z ostatnich 6 miesięcy (nie dostawca z kartoteki);
 * {@code nomenclature}: kod (cyfry, spacje pomijane) to początek kodu podrodziny, inny tekst to nazwa rejonu, rodziny
 * albo podrodziny. Przedziały cen z granicami włącznie. {@code sort}: kolumna (itm8, ean, name, brand,
 * nomenclature, purchase, sale, stock, rotation, domyślnie name), {@code direction}: asc albo desc. {@code page} od 0.
 */
public record CatalogListRequest(String itm8, String ean, String name, String brand, String supplier,
        String nomenclature, BigDecimal purchaseFrom, BigDecimal purchaseTo, BigDecimal saleFrom, BigDecimal saleTo,
        String sort, String direction, Integer page) {
}
