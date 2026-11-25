package com.intermanage.backend.agent.entity;

/** Makro, które można zlecić na komputerze sklepu (nazwy trafiają do bazy). Tylko makra składane przez serwer. */
public enum AgentMacroKind {
    /** Dostawa z bazy: kody ITM8 z błędem „ITM nie istnieje” z Obsługi BL bazy (wynik = lista kodów). */
    ITM8_COLLECT,
    /** Dostawa z bazy: nowe kody ITM8 na kartach artykułów. */
    ITM8_APPLY,
    /** Kody MEA: integracja plików cadencier z tymi kodami. */
    MEA_CODES,
    /** Dostawa z bazy: pliki bonu z Obsługi BL do zamówień i bony Excel do „Znajdź różnice”. */
    BASE_RECEIVE,
    /** Dostawa z bazy: Nr BL w nagłówkach zamówień i zakończenie dostaw. */
    BASE_CLOSE,
    /** Pliki nowych i zmienionych artykułów, które agent właśnie zapisał w folderze sklepu (zleca je serwer). */
    PRODUCT_FILES,
    /** Zmiany cen, które pojawiły się w folderze sklepu (zleca je serwer, na preferowanym komputerze). */
    PRICE_CONTROL,
    /** ITM8 z cadencier jako priorytetowe i aktywne; zleca je osoba albo serwer po aktualizacji danych. */
    CAD_PRIORITY
}
