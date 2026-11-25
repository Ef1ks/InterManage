package com.intermanage.backend.ksef.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Strona listy faktur KSeF (po {@value com.intermanage.backend.ksef.service.KsefInvoiceService#PAGE_SIZE});
 * {@code matchingCount} i sumy dotyczą wszystkich faktur pasujących do filtrów. {@code listedSuppliersOnly}: lista
 * pokazuje tylko dostawców z listy dostawców sklepu (konto bez wglądu we wszystkich dostawców). {@code nipLookup}:
 * takie konto wpisało NIP dostawcy spoza listy, więc widzi najwyżej 3 jego faktury z ostatniego miesiąca.
 */
public record KsefInvoicePage(
        List<KsefInvoiceSummary> invoices,
        int page,
        boolean hasMore,
        long matchingCount,
        BigDecimal netTotal,
        BigDecimal grossTotal,
        boolean listedSuppliersOnly,
        boolean nipLookup) {

    public KsefInvoicePage(List<KsefInvoiceSummary> invoices, int page, boolean hasMore, long matchingCount,
            BigDecimal netTotal, BigDecimal grossTotal) {
        this(invoices, page, hasMore, matchingCount, netTotal, grossTotal, false, false);
    }

    public KsefInvoicePage restricted(boolean nipLookup) {
        return new KsefInvoicePage(invoices, page, hasMore, matchingCount, netTotal, grossTotal, true, nipLookup);
    }
}
