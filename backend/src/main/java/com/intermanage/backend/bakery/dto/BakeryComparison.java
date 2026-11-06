package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Podsumowanie całej weryfikacji: suma WZ, faktura z korektami i przyjęcie po korektach, wiersz na artykuł.
 * {@code unavailable}: faktury, których XML nie udało się teraz pobrać; {@code pendingCorrections}: korekty dostawcy
 * bez odczytanego XML (mogą dotyczyć tej faktury). {@code hasBon}: czy jest przypięte przyjęcie.
 */
public record BakeryComparison(List<Row> rows, Totals totals, List<Unavailable> unavailable, long pendingCorrections,
        boolean hasBon, String sellerNip, String sellerName) {

    /** Co się nie zgadza: {@code WZ} ilość z WZ inna niż faktura z korektami, {@code BON} przyjęcie inne niż ona. */
    public enum Status {
        OK, WZ, BON, NO_ARTICLE
    }

    /**
     * {@code key}: ITM8 albo (pozycja bez artykułu) kod z faktury. {@code invoiceQuantity} i
     * {@code correctionQuantity} to ilość z faktur i zmiana z korekt, {@code finalQuantity} ich suma. {@code price}:
     * cena netto z faktury, bez niej z przyjęcia. {@code difference} = WZ - faktura z korektami,
     * {@code differenceValue} to ta różnica w cenie {@code price}. Pozycja bez artykułu ma {@code linkCode} do
     * przypięcia i jednostkę z faktury.
     */
    public record Row(String key, String itm8, String ean, String name, BigDecimal wzQuantity,
            BigDecimal invoiceQuantity, BigDecimal correctionQuantity, BigDecimal finalQuantity, BigDecimal price,
            BigDecimal invoiceValue, BigDecimal bonQuantity, BigDecimal bonValue, BigDecimal difference,
            BigDecimal differenceValue, Status status, String linkCode, String invoiceUnit) {
    }

    public record Totals(BigDecimal wzValue, BigDecimal invoiceValue, BigDecimal correctionValue,
            BigDecimal finalValue, BigDecimal bonValue, BigDecimal differenceValue, int differences) {
    }

    public record Unavailable(String invoiceNumber, String reason) {
    }
}
