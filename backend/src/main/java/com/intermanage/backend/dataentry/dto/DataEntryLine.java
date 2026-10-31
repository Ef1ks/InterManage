package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

/**
 * Pozycja faktury w tabeli wprowadzania danych. {@code ean} z GTIN-u (GTIN-14 jako EAN-13), {@code unitNet} cena
 * jednostkowa netto (z brutto, gdy faktura podaje tylko je), {@code vatPercent} stawka w procentach ({@code null} przy
 * nieznanej). {@code product} to artykuł z kartoteki, a bez niego {@code unit} i {@code netContent} wyliczone z nazwy
 * z faktury.
 */
public record DataEntryLine(String number, String ean, BigDecimal quantity, BigDecimal unitNet, String invoiceName,
        String invoiceUnit, BigDecimal vatPercent, CatalogProduct product, String unit, BigDecimal netContent) {
}
