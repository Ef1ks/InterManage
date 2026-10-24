package com.intermanage.backend.permission;

/**
 * Kategorie narzędzi, do których właściciel nadaje dostęp w rolach.
 * Nowy moduł aplikacji = nowa stała tutaj; nazwa stałej trafia do bazy, więc nie zmieniamy istniejących.
 */
public enum PermissionCategory {

    SALES("Sprzedaż i raporty", "Obroty, marża, raporty sprzedaży"),
    INVENTORY("Magazyn i produkty", "Katalog produktów, stany magazynowe, inwentaryzacja"),
    ORDERS("Zamówienia i dostawy", "Zamówienia do dostawców, przyjęcia towaru"),
    STAFF("Pracownicy i grafik", "Grafik zmian, czas pracy"),
    IMPORTS("Import danych", "Wgrywanie plików Excel i formularze ręczne");

    private final String label;
    private final String description;

    PermissionCategory(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
