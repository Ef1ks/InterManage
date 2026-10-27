package com.intermanage.backend.ksef.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Metadane faktury z KSeF ({@code POST /invoices/query/metadata}), tylko pola, których używamy. Kwoty w walucie
 * faktury, poza {@code vatAmount} (zawsze w PLN). {@code thirdSubjects} to Podmioty 3, w tym IDWew odbiorcy.
 */
public record KsefInvoiceMetadata(
        String ksefNumber,
        String invoiceNumber,
        LocalDate issueDate,
        OffsetDateTime invoicingDate,
        OffsetDateTime permanentStorageDate,
        Seller seller,
        Buyer buyer,
        BigDecimal netAmount,
        BigDecimal grossAmount,
        BigDecimal vatAmount,
        String currency,
        String invoiceType,
        boolean hasAttachment,
        List<ThirdSubject> thirdSubjects) {

    public record Seller(String nip, String name) {
    }

    public record Buyer(Identifier identifier, String name) {
    }

    /** {@code type}: Nip, InternalId, VatUe, Other, None. */
    public record Identifier(String type, String value) {
    }

    public record ThirdSubject(Identifier identifier, String name, Integer role) {
    }

    /** Strona wyników; {@code isTruncated} = osiągnięty limit 10 000 rekordów dla tych filtrów. */
    public record Page(boolean hasMore, boolean isTruncated, OffsetDateTime permanentStorageHwmDate,
            List<KsefInvoiceMetadata> invoices) {
    }
}
