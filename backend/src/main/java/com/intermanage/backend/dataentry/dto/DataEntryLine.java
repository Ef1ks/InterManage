package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pozycja faktury w tabeli wprowadzania danych. {@code ean} z GTIN-u (GTIN-14 jako EAN-13), {@code unitNet} cena
 * jednostkowa netto (z brutto, gdy faktura podaje tylko je), {@code vatPercent} stawka w procentach ({@code null} przy
 * nieznanej). {@code product} to artykuł z kartoteki, a bez niego {@code unit} i {@code netContent} wyliczone z nazwy
 * z faktury. {@code eanSource}: źródło kodu ({@code GTIN}, {@code INDEX}, {@code EXTRA:klucz}, {@code NAME}), przy
 * automatycznym wyborze różne dla pozycji. Bez EAN-u {@code fallbackCode} to inny kod z faktury (Indeks, dodatkowy
 * opis, GTIN, który nie jest EAN-em) albo nazwa, a {@code fallbackSource} opis pola, z którego pochodzi. Pozycja
 * wczytana z przypięcia kodu dostawcy do artykułu ma {@code eanSource} = LINK, {@code linkId} i {@code linkedCode}
 * (przypięty kod).
 */
public record DataEntryLine(String number, String ean, String eanSource, String fallbackCode, String fallbackSource,
        BigDecimal quantity, BigDecimal unitNet, String invoiceName,
        String invoiceUnit, BigDecimal vatPercent, CatalogProduct product, String unit, BigDecimal netContent,
        UUID linkId, String linkedCode) {
}
