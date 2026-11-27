package com.intermanage.backend.collector.entity;

import com.intermanage.backend.store.entity.ProtocolKind;

/**
 * Co powstało z listy z kolektora: protokół (te same nazwy co {@link ProtocolKind}, bo tak zapisywano je od V200),
 * zamówienie (plik zamówienia z artykułów listy), inwentaryzacja (plik inwentaryzacji albo przygotowana na później)
 * albo zwrot do dostawcy (lista w Zakupach).
 */
public enum CollectorListOutput {

    LIQUIDATION,
    STORE_NEEDS,
    ADVERTISING,
    DONATION,
    REPRESENTATION,
    TRANSFER,
    ORDER,
    INVENTORY,
    SUPPLIER_RETURN;

    public static CollectorListOutput of(ProtocolKind kind) {
        return valueOf(kind.name());
    }

    /** Nazwa do wydruku: protokołu jak w {@link ProtocolKind}, pozostałe po polsku. */
    public String label() {
        return switch (this) {
            case ORDER -> "Zamówienie";
            case INVENTORY -> "Inwentaryzacja";
            case SUPPLIER_RETURN -> "Zwrot do dostawcy";
            default -> ProtocolKind.valueOf(name()).label();
        };
    }
}
