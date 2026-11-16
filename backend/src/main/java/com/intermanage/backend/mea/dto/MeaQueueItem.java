package com.intermanage.backend.mea.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Plik zamówienia MEA czekający w kolejce sklepu na zapis w folderze ATHOS: kto i kiedy go zrobił, ile pozycji
 * i kartonów, tytuł MEA i koniec rezerwacji (po nim ATHOS go już nie przyjmie, więc wysyłka go pomija).
 */
public record MeaQueueItem(UUID id, String number, String title, String fileName, int positions, int colis,
        String createdBy, Instant queuedAt, LocalDate reservationTo) {
}
