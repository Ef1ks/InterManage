package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.staff.entity.TimeRequestKind;
import com.intermanage.backend.staff.entity.TimeRequestStatus;

/**
 * Różnice między odbiciami a grafikiem w karcie zalogowanego pracownika z ostatnich dni. {@code employee}: konto jest
 * pracownikiem. {@code open}: ile różnic czeka na wniosek (bez wniosku albo z wycofanym).
 */
public record MyTimeAnomaliesResponse(boolean employee, int open, List<Item> items) {

    /**
     * {@code part}: OVERTIME (czas poza grafikiem), SHORTFALL (brak czasu z grafiku) albo MISSING_EXIT (wejście bez
     * wyjścia, na to wniosku się nie składa: tylko kierownik poprawia odbicie). {@code kinds}: wnioski, które pasują.
     * {@code request}: ostatni wniosek na ten odcinek.
     */
    public record Item(LocalDate date, String part, LocalDateTime start, LocalDateTime end, int minutes,
            LocalDateTime plannedStart, LocalDateTime plannedEnd, List<TimeRequestKind> kinds, Request request) {
    }

    public record Request(UUID id, TimeRequestKind kind, TimeRequestStatus status) {
    }
}
