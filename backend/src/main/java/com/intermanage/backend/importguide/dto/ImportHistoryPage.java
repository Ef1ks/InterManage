package com.intermanage.backend.importguide.dto;

import java.time.Instant;
import java.util.List;

/**
 * Porcja wspólnej historii importów, od najnowszego wgrania. {@code hasMore}: są starsze, kolejną porcję daje
 * {@code before} = {@code importedAt} ostatniej pozycji.
 */
public record ImportHistoryPage(List<Entry> items, boolean hasMore) {

    /**
     * Jedno wgranie: {@code kind} to rodzaj raportu (products, documents, suppliers, sales, nomenclatures,
     * promotions, productCodes, cadencier), a {@code item} odpowiedź importu tego raportu.
     */
    public record Entry(String kind, Instant importedAt, Object item) {
    }
}
