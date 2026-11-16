package com.intermanage.backend.mea.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pozycja listy MEA z portalu: numer, nazwa (tekst), okres promocji, okno rezerwacji, czy zamówienie złożono
 * w Mercalys ({@code ordered}, flaga z portalu) i pliki formularza. {@code athos*}: najnowsza wysyłka zamówienia tego
 * MEA z ATHOS z dziennika Histo.Log (data, pozycje, kartony), pusta bez wysyłki albo bez odczytanego dziennika.
 * {@code order*}: najnowsze zamówienie zapisane do ATHOS z aplikacji (plik R… w folderze comrnis).
 */
public record MeaItem(
        String number,
        String name,
        LocalDate promoFrom,
        LocalDate promoTo,
        LocalDate reservationFrom,
        LocalDate reservationTo,
        boolean ordered,
        MeaTab tab,
        List<String> files,
        LocalDateTime athosSentAt,
        Integer athosPositions,
        Integer athosColis,
        Instant orderSavedAt,
        Integer orderColis) {
}
