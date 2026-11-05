package com.intermanage.backend.leaflet.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Wynik sprawdzarki etykiet: artykuły pasujące do zapytania, od będących w gazetce dziś. {@code total} to wszystkie
 * trafienia, {@code articles} najwyżej pierwsze 30.
 */
public record LeafletLabelCheck(int total, List<Article> articles) {

    /** {@code CURRENT}: promocja trwa dziś, {@code UPCOMING}: dopiero się zacznie, {@code NONE}: nie było w gazetkach. */
    public enum Status {
        CURRENT, UPCOMING, PAST, NONE
    }

    /**
     * Artykuł z kartoteki, a bez niego pozycja gazetki, której EAN-u kartoteka nie zna. {@code lines}: trwające
     * i przyszłe promocje, a gdy żadna nie trwa, także ostatnia zakończona. {@code status} to status pierwszej z nich.
     */
    public record Article(String ean, String name, String brand, Status status, List<Line> lines) {
    }

    /** Promocja z gazetki {@code week}/{@code year}; {@code shelfLabel} to OZNACZENIE PRZY PÓŁCE z pliku. */
    public record Line(int year, int week, Status status, LocalDate dateFrom, LocalDate dateTo, String shelfLabel) {
    }
}
