package com.intermanage.backend.basedelivery.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Odczytany „Tymczasowy bon dostawy” z Mercalys (dostawa z bazy wprowadzona do programu sklepu) do porównania
 * z tabelą dostawy z bazy. Nie jest zapisywany.
 *
 * @param supplierCode kod dostawcy z „Dostawca 539”
 * @param number numer dostawy w programie sklepu
 */
public record TemporaryNoteResponse(
        String fileName,
        String supplierCode,
        String supplierName,
        String number,
        LocalDateTime createdAt,
        List<Line> lines) {

    public TemporaryNoteResponse withLines(List<Line> lines) {
        return new TemporaryNoteResponse(fileName, supplierCode, supplierName, number, createdAt, lines);
    }

    /**
     * Linia bonu.
     *
     * @param itm8 Kod ITM8 z kartoteki sklepu (13 cyfr)
     * @param reference Referencja dostawcy: ITM8 z pliku bazy
     * @param catalogEans wszystkie EAN-y artykułu z kartoteki sklepu (bieżący, multikody, dawne pary)
     */
    public record Line(
            String itm8,
            String ean,
            String reference,
            String name,
            BigDecimal vatPercent,
            BigDecimal quantity,
            BigDecimal purchasePrice,
            BigDecimal salePrice,
            BigDecimal netValue,
            List<String> catalogEans) {

        public Line withCatalogEans(List<String> eans) {
            return new Line(itm8, ean, reference, name, vatPercent, quantity, purchasePrice, salePrice, netValue,
                    eans);
        }
    }
}
