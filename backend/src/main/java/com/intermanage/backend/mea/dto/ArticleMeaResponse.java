package com.intermanage.backend.mea.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bieżąca albo nadchodząca gazetka MEA artykułu w karcie artykułu (promocja jeszcze się nie skończyła). Pozycję
 * formularza wskazuje ITM8 promocyjny, ITM8 równoległy albo EAN artykułu. {@code dispatches}: rozrzuty R1-R4 z datą,
 * a {@code colis} to kartony tego artykułu z zamówienia zapisanego w aplikacji ({@code null}, gdy ilości nie znamy,
 * np. zamówienie wyszło z eMEA). {@code athosSentAt} i {@code athosColis}: ostatnia wysyłka tego MEA z dziennika
 * ATHOS (cały MEA, bez rozbicia na artykuły).
 */
public record ArticleMeaResponse(
        String number,
        String title,
        LocalDate promoFrom,
        LocalDate promoTo,
        LocalDate reservationTo,
        String meaItm8,
        Integer unitsPerPack,
        Status status,
        Instant orderedAt,
        String orderedBy,
        LocalDateTime athosSentAt,
        Integer athosColis,
        List<Dispatch> dispatches) {

    /**
     * {@code ORDERED} zamówione z aplikacji z kartonami tego artykułu, {@code NOT_ORDERED} zamówienie z aplikacji bez
     * tego artykułu, {@code QUEUED} plik czeka w kolejce do ATHOS, {@code SENT} wysłane z ATHOS bez znanych ilości
     * artykułu, {@code OPEN} rezerwacja otwarta i nic nie wysłano, {@code CLOSED} rezerwacja zamknięta i według
     * dziennika ATHOS nic nie wysłano, {@code UNKNOWN} rezerwacja zamknięta, a sklep nie wczytał dziennika ATHOS.
     */
    public enum Status {
        ORDERED, NOT_ORDERED, QUEUED, SENT, OPEN, CLOSED, UNKNOWN
    }

    /** Rozrzut: numer od 1, data dostawy z formularza i zamówione kartony ({@code null} = nie wiadomo). */
    public record Dispatch(int index, LocalDate date, Integer colis) {
    }
}
