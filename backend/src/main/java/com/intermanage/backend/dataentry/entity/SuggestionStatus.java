package com.intermanage.backend.dataentry.entity;

/** Pewność podrodziny zaproponowanej nowemu artykułowi; nazwa stałej trafia do bazy. */
public enum SuggestionStatus {

    /** Warstwa bez modelu: producent (prefiks EAN) i podobne nazwy wskazują tę samą podrodzinę. */
    CERTAIN("Pewne"),
    /** Model zgodny z podobnymi nazwami albo z producentem i pewny swojej odpowiedzi. */
    AGREED("Zgodne"),
    /** Model niepewny albo niezgodny ze wskazówkami: podrodzinę wybiera człowiek z propozycji. */
    TO_CHECK("Do sprawdzenia");

    private final String label;

    SuggestionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
