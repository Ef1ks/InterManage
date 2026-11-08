package com.intermanage.backend.auth.entity;

/**
 * Kategorie narzędzi, do których właściciel nadaje dostęp w rolach.
 * Nowy moduł aplikacji = nowa stała tutaj; nazwa stałej trafia do bazy, więc nie zmieniamy istniejących.
 */
public enum PermissionCategory {

    SALES("Sprzedaż i raporty", "Sprzedaż, marża i straty, weryfikacja zakupów, promocje, braki i zerówki"),
    INVENTORY("Magazyn i produkty", "Kartoteka towarów: stany, rotacja, ceny, sprzedaż i dostawy artykułu, przeceny i cenówki"),
    ORDERS("Zamówienia i dostawy", "Zamówienia do dostawców, przyjęcia towaru"),
    STAFF("Pracownicy", "Pracownicy, działy i stanowiska, godziny pracy"),
    IMPORTS("Import danych", "Codzienne raporty z systemu sklepu: kartoteka, multikody, promocje, sprzedaż, dokumenty"),
    DELI_ORDERS("Zamówienie garmażu", "Formularz zamówień działów: rzeźnia, garmaż i inne"),
    DOCUMENTS("Dokumenty", "Dokumenty magazynowe z systemu sklepu: przyjęcia PZ i kolejne typy"),
    DELI_PRICES("Kalkulator cen garmażu", "Ceny sprzedaży wyrobów z przepisów i cen zakupu składników"),
    KSEF_INVOICES("Faktury KSeF", "Faktury zakupowe z Krajowego Systemu e-Faktur i połączenie sklepu z KSeF"),
    DATA_ENTRY("Wprowadzanie danych", "Faktury KSeF i pliki XML w tabeli jak w Excelu: nowe artykuły, ceny, protokoły i pliki dla programu sklepu"),
    SUPPLIERS("Dostawcy", "Kartoteka dostawców: strona internetowa, przedstawiciele i kontakty"),
    MEAT_PURCHASES("Zakup mięsa", "Ceny zakupu mięsa z dokumentów WZ (skan odczytuje AI) i porównanie sklepów"),
    DELI_PRODUCTION("Karty produkcji", "Karty produkcji garmażu (CCP1): formularz do wydruku, ilości ze skanera, baza artykułów"),
    STAFF_RECORDS("Kadry", "Profile pracowników: umowy, staż, urlopy, orzeczenia o niepełnosprawności. Tylko dla osób upoważnionych"),
    STAFF_SCHEDULE("Grafik", "Miesięczne grafiki działów: układanie, publikacja i wersje przekazane pracownikom"),
    STAFF_TIME("Czas pracy", "Karty czasu pracy z odbić kart NFC: realny czas pracy, akceptacja nadgodzin i spóźnień");

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
