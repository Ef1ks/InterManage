package com.intermanage.backend.dataentry.dto;

import java.util.List;

/** Strona okna wczytywania faktur (po 100, kolejne przy przewijaniu); {@code matchingCount} = wszystkie pasujące. */
public record DataEntryInvoicePage(List<DataEntryInvoiceItem> invoices, int page, boolean hasMore,
        long matchingCount) {
}
