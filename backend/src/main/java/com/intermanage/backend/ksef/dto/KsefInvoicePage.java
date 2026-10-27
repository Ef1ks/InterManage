package com.intermanage.backend.ksef.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Strona listy faktur KSeF (po {@value com.intermanage.backend.ksef.service.KsefInvoiceService#PAGE_SIZE});
 * {@code matchingCount} i sumy dotyczą wszystkich faktur pasujących do filtrów.
 */
public record KsefInvoicePage(
        List<KsefInvoiceSummary> invoices,
        int page,
        boolean hasMore,
        long matchingCount,
        BigDecimal netTotal,
        BigDecimal grossTotal) {
}
