package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

/**
 * Artykuł z kartoteki sklepu dla EAN-u ({@code ean} bez zer wiodących, tak jak go szukano). {@code unit} to rodzaj
 * sprzedaży (kg, L, szt.), {@code salePrice} cena sprzedaży brutto sztuki.
 */
public record CatalogProduct(String ean, String itm8, String name, String brand, BigDecimal salePrice, String unit,
        BigDecimal netContent, String subfamilyCode) {
}
