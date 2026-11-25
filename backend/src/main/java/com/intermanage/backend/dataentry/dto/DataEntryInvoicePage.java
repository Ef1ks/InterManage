package com.intermanage.backend.dataentry.dto;

import java.util.List;

/**
 * Strona okna wczytywania faktur (po 100, kolejne przy przewijaniu); {@code matchingCount} = wszystkie pasujące.
 * {@code listedSuppliersOnly}: konto widzi tylko dostawców z listy dostawców sklepu, {@code nipLookup}: wpisany NIP
 * dostawcy spoza listy (najwyżej 3 faktury z ostatniego miesiąca).
 */
public record DataEntryInvoicePage(List<DataEntryInvoiceItem> invoices, int page, boolean hasMore,
        long matchingCount, boolean listedSuppliersOnly, boolean nipLookup) {
}
