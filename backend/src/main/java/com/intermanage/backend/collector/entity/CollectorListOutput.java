package com.intermanage.backend.collector.entity;

import com.intermanage.backend.store.entity.ProtocolKind;

/**
 * Co powstało z listy z kolektora: protokół (te same nazwy co {@link ProtocolKind}, bo tak zapisywano je od V200)
 * albo zamówienie (plik zamówienia z artykułów listy, od 1.8.3 programu kolektora bez zmian po jego stronie).
 */
public enum CollectorListOutput {

    LIQUIDATION,
    STORE_NEEDS,
    ADVERTISING,
    DONATION,
    REPRESENTATION,
    TRANSFER,
    ORDER;

    public static CollectorListOutput of(ProtocolKind kind) {
        return valueOf(kind.name());
    }

    /** Nazwa do wydruku: protokołu jak w {@link ProtocolKind}, zamówienia „Zamówienie”. */
    public String label() {
        return this == ORDER ? "Zamówienie" : ProtocolKind.valueOf(name()).label();
    }
}
