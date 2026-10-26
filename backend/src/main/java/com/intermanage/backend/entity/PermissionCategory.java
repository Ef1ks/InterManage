package com.intermanage.backend.entity;

/**
 * Kategorie narzędzi, do których właściciel nadaje dostęp w rolach.
 * Nowy moduł aplikacji = nowa stała tutaj; nazwa stałej trafia do bazy, więc nie zmieniamy istniejących.
 */
public enum PermissionCategory {

    SALES("Sprzedaż i raporty", "Obroty, marża, raporty sprzedaży"),
    INVENTORY("Magazyn i produkty", "Katalog produktów, stany magazynowe, inwentaryzacja"),
    ORDERS("Zamówienia i dostawy", "Zamówienia do dostawców, przyjęcia towaru"),
    STAFF("Pracownicy i grafik", "Grafik zmian, czas pracy"),
    IMPORTS("Import danych", "Wgrywanie plików Excel i formularze ręczne"),
    DELI_ORDERS("Zamówienie garmażu", "Formularz zamówień działów: rzeźnia, garmaż i inne"),
    DOCUMENTS("Dokumenty", "Dokumenty magazynowe z systemu sklepu: przyjęcia PZ i kolejne typy"),
    DELI_PRICES("Kalkulator cen garmażu", "Koszt wyrobów na kg z przepisów i cen zakupu z kartoteki"),
    KSEF_INVOICES("Faktury KSeF", "Faktury zakupowe z Krajowego Systemu e-Faktur i połączenie sklepu z KSeF");

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
