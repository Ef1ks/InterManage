package com.intermanage.backend.sales.dto;

import java.time.LocalDate;

/**
 * Gazetka artykułu w porównaniu sprzedaży: MEA z numerem i tytułem albo gazetka sklepu z pliku (numer i tytuł
 * puste), z okresem promocji. {@code previous} i {@code current} mówią, czy promocja nachodzi na okres porównania
 * i okres aktualny.
 */
public record ArticleLeafletMark(
        String number,
        String title,
        LocalDate from,
        LocalDate to,
        boolean previous,
        boolean current) {
}
