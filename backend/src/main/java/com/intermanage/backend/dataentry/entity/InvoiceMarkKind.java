package com.intermanage.backend.dataentry.entity;

/** Co sklep zrobił z fakturą w oknie wczytywania (nazwa trafia do bazy). */
public enum InvoiceMarkKind {
    /** Wczytana do tabeli. */
    LOADED,
    /** Pobrana albo wydrukowana: tylko informacja, faktura dalej czeka na wczytanie. */
    DOWNLOADED,
    /** Nie dotyczy sklepu: znika z listy jak wczytana, da się ją przywrócić. */
    NOT_MINE;

    /** Znacznik, który zdejmuje fakturę z listy "Do wczytania" (pobranie nie zdejmuje). */
    public boolean handled() {
        return this != DOWNLOADED;
    }
}
