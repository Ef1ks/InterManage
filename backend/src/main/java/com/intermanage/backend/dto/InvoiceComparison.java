package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Przyjęcie PZ (po korektach) porównane pozycja po pozycji z fakturami KSeF: pozycje faktur łączą się z artykułami
 * przyjęcia po kodzie EAN (GTIN z faktury, także dawne EAN-y artykułu z kartoteki). {@code rows}: najpierw różnice,
 * od największej. {@code unavailable}: faktury, których XML nie udało się teraz pobrać (np. limit KSeF).
 */
public record InvoiceComparison(
        List<String> invoices,
        List<Unavailable> unavailable,
        BigDecimal invoiceNet,
        BigDecimal deliveryValue,
        List<Row> rows) {

    public record Unavailable(String invoiceNumber, String message) {
    }

    /**
     * Artykuł: wartości z przyjęcia i z faktur ({@code null}, gdy go tam nie ma), {@code difference} = faktura -
     * przyjęcie. {@code name}: z przyjęcia, a gdy go tam nie ma - z faktury.
     */
    public record Row(
            Status status,
            String ean,
            String itm8,
            String name,
            String invoiceName,
            BigDecimal deliveryQuantity,
            BigDecimal deliveryPrice,
            BigDecimal deliveryValue,
            BigDecimal invoiceQuantity,
            BigDecimal invoicePrice,
            BigDecimal invoiceValue,
            BigDecimal difference) {
    }

    /**
     * MATCH: wartości zgodne (do grosza); PRICE: ta sama ilość, inna cena; QUANTITY: inna ilość (albo inna jednostka
     * na fakturze, np. kartony); MISSING_ON_INVOICE: artykułu przyjęcia nie ma na fakturze; MISSING_IN_DELIVERY: pozycja
     * faktury z kodem, którego nie ma w przyjęciu; NO_CODE: pozycja faktury bez kodu EAN.
     */
    public enum Status {
        MATCH, PRICE, QUANTITY, MISSING_ON_INVOICE, MISSING_IN_DELIVERY, NO_CODE
    }
}
