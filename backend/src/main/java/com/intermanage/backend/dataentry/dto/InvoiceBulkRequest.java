package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

import com.intermanage.backend.dataentry.service.DataEntryInvoiceService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Działanie na zaznaczonych fakturach okna wczytywania: LOADED / NOT_MINE dodaje znacznik, RESTORE wraca do wczytania.
 * Zaznaczenie to podane {@code invoiceIds} albo wszystkie pasujące do {@code filter} (to samo co lista) bez
 * {@code excludeIds}.
 */
public record InvoiceBulkRequest(@NotNull Action action,
        @Size(max = DataEntryInvoiceService.BULK_LIMIT) List<UUID> invoiceIds,
        @Valid Filter filter, List<UUID> excludeIds) {

    public enum Action {
        LOADED, NOT_MINE, RESTORE
    }

    public record Filter(String query, String internalId, String kind, DataEntryInvoiceService.ListStatus status,
            Boolean showSkipped, Boolean myDeliveries, Boolean quantityChanges) {
    }
}
