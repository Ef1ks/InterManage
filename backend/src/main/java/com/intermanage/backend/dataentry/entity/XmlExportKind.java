package com.intermanage.backend.dataentry.entity;

/** Rodzaj pliku XML we wprowadzaniu danych (nazwa trafia do bazy). */
public enum XmlExportKind {
    /** "Zapisz XML": w historii, gdy przeglądarka zapisała plik na dysku. */
    SAVED,
    /** "Zapisz tymczasowo": bez zapisu na dysku, najwyżej 10 w sklepie. */
    TEMPORARY
}
