package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

import com.intermanage.backend.document.entity.DocumentType;

/** Jedna linia dokumentu z raportu Wejścia/Wyjścia, już sprawdzona i przeliczona na typy z bazy. */
public record DocumentLineRow(
        DocumentType type,
        String documentNumber,
        LocalDateTime documentDate,
        String supplierDocumentNumber,
        int lineNumber,
        String supplierCode,
        String supplierName,
        String nomenclatureCode,
        String nomenclatureName,
        String itm8,
        String ean,
        String name,
        String brand,
        String unit,
        BigDecimal quantity,
        BigDecimal purchasePrice,
        BigDecimal salePriceCurrent,
        BigDecimal salePriceDocument,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        BigDecimal saleValueNet,
        Short vatRate,
        BigDecimal vatValue,
        BigDecimal marginValue,
        BigDecimal marginPercent) {

    /** Linię w dokumencie wyznacza numer razem z ITM8 (zwroty prasy mają wszystkie linie z numerem 1). */
    public String lineKey() {
        return lineNumber + "/" + itm8;
    }

    /**
     * Ta sama linia z tymi samymi liczbami: raport podwaja wiersz, gdy słownik ma dla kodu dwie wartości, np. kod
     * dostawcy dwie nazwy (PPP 240490000005 z 12.10.2024, kod 90: NORIMPEX i LACOSTA). Opisy mogą się różnić,
     * liczby nie, więc taki wiersz to powtórzenie, a nie kolejna część linii.
     */
    public boolean sameFigures(DocumentLineRow other) {
        return same(quantity, other.quantity) && same(purchasePrice, other.purchasePrice)
                && same(salePriceCurrent, other.salePriceCurrent) && same(salePriceDocument, other.salePriceDocument)
                && same(purchaseValue, other.purchaseValue) && same(saleValueGross, other.saleValueGross)
                && same(saleValueNet, other.saleValueNet) && same(vatValue, other.vatValue)
                && same(marginValue, other.marginValue) && Objects.equals(vatRate, other.vatRate);
    }

    /**
     * Linia złożona z dwóch wierszy raportu o tym samym numerze i ITM8, ale innych liczbach (system sklepu rozbija
     * linię na części): ilości i wartości się sumują, ceny i marża % liczą się z sum, opisy zostają z pierwszego.
     */
    public DocumentLineRow plus(DocumentLineRow other) {
        BigDecimal sumQuantity = add(quantity, other.quantity);
        BigDecimal sumPurchase = add(purchaseValue, other.purchaseValue);
        BigDecimal sumGross = add(saleValueGross, other.saleValueGross);
        BigDecimal sumMargin = add(marginValue, other.marginValue);
        return new DocumentLineRow(type, documentNumber, documentDate, supplierDocumentNumber, lineNumber,
                supplierCode, supplierName, nomenclatureCode, nomenclatureName, itm8, ean, name, brand, unit,
                sumQuantity, unitPrice(sumPurchase, sumQuantity, purchasePrice), salePriceCurrent,
                unitPrice(sumGross, sumQuantity, salePriceDocument), sumPurchase, sumGross,
                add(saleValueNet, other.saleValueNet), vatRate, add(vatValue, other.vatValue), sumMargin,
                sumMargin == null || sumGross == null || sumGross.signum() == 0 ? marginPercent
                        : sumMargin.multiply(HUNDRED).divide(sumGross, 2, RoundingMode.HALF_UP));
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.add(b);
    }

    /** Cena z sumy wartości i ilości (jak w raporcie, do 4 miejsc); bez ilości zostaje cena pierwszego wiersza. */
    private static BigDecimal unitPrice(BigDecimal value, BigDecimal quantity, BigDecimal fallback) {
        if (value == null || quantity == null || quantity.signum() == 0) {
            return fallback;
        }
        return value.divide(quantity, 4, RoundingMode.HALF_UP);
    }
}
