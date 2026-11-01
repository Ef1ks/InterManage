package com.intermanage.backend.dataentry.entity;

/** Co okno wczytywania robi z fakturami dostawcy z listy (nazwa trafia do bazy). */
public enum SupplierRuleMode {
    /** Pomijany: jego faktur nie ma w oknie (pokazuje je dopiero przełącznik "Pomijani dostawcy"). */
    HIDDEN,
    /** Odkładany: jego faktury od razu są w "Wczytane", bo wprowadza się tylko niektóre z nich. */
    DONE
}
