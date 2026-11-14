package com.intermanage.backend.importguide.entity;

/** Raporty z Importu danych. Nazwa stałej trafia do bazy ({@code import_guides.kind}), więc istniejących nie zmieniamy. */
public enum ImportReportKind {
    PRODUCTS,
    PRODUCT_CODES,
    PROMOTIONS,
    SALES,
    DOCUMENTS,
    SUPPLIERS,
    NOMENCLATURES,
    CADENCIER
}
