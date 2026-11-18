package com.intermanage.backend.ai.entity;

/** Do czego posłużyło zapytanie do modelu AI. Nazwa stałej trafia do bazy ({@code ai_usage.feature}). */
public enum AiFeature {
    /** Propozycje nazwy, marki i nomenklatury nowych artykułów we wprowadzaniu danych. */
    DATA_ENTRY_SUGGESTIONS,
    /** Skracanie za długich nazw artykułów. */
    DATA_ENTRY_NAMES,
    /** Odczyt skanu WZ w zakupie mięsa. */
    MEAT_SCAN
}
