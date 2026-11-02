package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Artykuł z kartoteki sklepu dla EAN-u ({@code ean} bez zer wiodących, tak jak go szukano; {@code catalogEan} to
 * bieżący EAN artykułu, inny np. dla kodu z etykiety wagi). {@code purchasePrice} to cena zakupu netto sztuki (przy
 * wagowych 1 kg) z kartoteki, {@code vatPercent} stawka z ostatniego dokumentu z tym artykułem. {@code unit} to rodzaj
 * sprzedaży (kg, L, szt.), {@code salePrice} cena sprzedaży brutto sztuki,
 * {@code cadencier} = artykuł ma CENA_CAD, {@code cadencierPrice} to ta cena (brutto sztuki narzucona przez centralę),
 * {@code promoPrice} trwająca promocja z pliku TRACEART (cena brutto na kasie, {@code salePrice} jest wtedy regularna) do
 * {@code promoTo}, {@code stock} i {@code rotation} to stan magazynowy i rotacja z ostatniego raportu kartoteki.
 */
public record CatalogProduct(String ean, String itm8, String catalogEan, String name, String brand,
        BigDecimal purchasePrice, BigDecimal vatPercent, BigDecimal salePrice, boolean cadencier,
        BigDecimal cadencierPrice, BigDecimal promoPrice, LocalDate promoTo, String unit, BigDecimal netContent, String subfamilyCode, BigDecimal stock,
        BigDecimal rotation) {
}
