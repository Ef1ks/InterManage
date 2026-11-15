package com.intermanage.backend.mea.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Pozycja listy MEA z portalu: numer, nazwa (tekst), okres promocji, okno rezerwacji, czy zamówienie złożono i pliki
 * formularza. Wartość, kolisy i rozrzuty są dopiero w samym dokumencie MEA, więc tu ich nie ma.
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
        List<String> files) {
}
