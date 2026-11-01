package com.intermanage.backend.dataentry.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.dataentry.entity.InvoiceMarkKind;
import com.intermanage.backend.dataentry.entity.SupplierRuleMode;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;

/**
 * Faktura w oknie wczytywania: dane z KSeF, status w sklepie ({@code null} = do wczytania, LOADED wczytana, NOT_MINE
 * nie moja; przy obu nie moja), {@code downloadedAt}: kiedy sklep ją pobrał albo wydrukował (tylko informacja, pobrana
 * dalej czeka na wczytanie), reguła dostawcy ({@code supplierRule}: pomijany albo odkładany do wczytanych) i znaczniki
 * innych sklepów firmy ({@code otherStores}, np. wczytana na innym stanowisku).
 */
public record DataEntryInvoiceItem(KsefInvoiceSummary invoice, InvoiceMarkKind status, Instant statusAt,
        Instant downloadedAt, SupplierRuleMode supplierRule, List<OtherStore> otherStores) {

    public record OtherStore(UUID storeId, String store, InvoiceMarkKind kind, Instant at) {
    }
}
