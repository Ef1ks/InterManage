package com.intermanage.backend.deliprice.entity;

/** Skąd pochodzi cena składnika kalkulacji. Nazwa stałej trafia do bazy. */
public enum PriceSource {
    /** Ostatnia dostawa: linia przyjęcia towaru (PZ) z raportu Wejścia/Wyjścia. */
    DELIVERY,
    /** Cena zakupu z kartoteki (raport produktów), gdy produktu nie było w dostawach. */
    CATALOG
}
