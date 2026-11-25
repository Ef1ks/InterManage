package com.intermanage.backend.agent.entity;

/** Funkcje agenta do włączenia i wyłączenia w sklepie (nazwa trafia do bazy, kolejność jest kolejnością ekranu). */
public enum AgentFeature {
    /** Zapis plików dla programu sklepu (wyłączony: zapisuje przeglądarka). */
    FILE_SAVING,
    /** Pliki cen z kasy (wyłączone: czyta je przeglądarka). */
    PRICE_FILES,
    /** Potwierdzanie zmian cen wczytanych przez program sklepu. */
    PRICE_CONTROL_FILES,
    /** Dziennik wysyłek ATHOS. */
    ATHOS,
    /** Codzienne czyszczenie folderu nowych artykułów. */
    PRODUCT_FILE_CLEANUP,
    /** Wgrywanie raportów Importu danych. */
    REPORTS,
    /** Usuwanie wgranych raportów z folderu następnego dnia. */
    REPORT_CLEANUP,
    /** Aktualizacja danych z Mercalys przy starcie komputera (ręczna z menu agenta działa dalej). */
    DATA_UPDATE,
    /** Plik nowych artykułów zapisany przez agenta sam wczytuje makro. */
    PRODUCT_FILE_MACRO,
    /** Zmiana cen w folderze sama wchodzi do Mercalys makrem. */
    PRICE_CONTROL_MACRO,
    /** ITM8 z cadencier po aktualizacji danych; przełącznik jest też na swojej stronie, domyślnie wyłączony. */
    CAD_PRIORITY_MACRO,
    /** Makra zlecane z przeglądarki na komputer sklepu. */
    REMOTE_MACROS
}
