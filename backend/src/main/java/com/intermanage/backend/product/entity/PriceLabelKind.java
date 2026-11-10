package com.intermanage.backend.product.entity;

/** Rodzaj wpisu na liście cenówek; nazwa stałej trafia do bazy. Każdy rodzaj ma własny format cenówki w pliku ETIQUETTE. */
public enum PriceLabelKind {

    /** Przecena z okresem (karta artykułu, ustawianie promocji): cenówka promocyjna. */
    MARKDOWN("PRO_PRO_FOR_67"),
    /** Zmiana ceny sprzedaży z pliku CTRLPRIX we wprowadzaniu danych: zwykła biała cenówka. */
    PRICE_CHANGE("BIAL_FOR_67");

    private final String labelFormat;

    PriceLabelKind(String labelFormat) {
        this.labelFormat = labelFormat;
    }

    public String labelFormat() {
        return labelFormat;
    }
}
