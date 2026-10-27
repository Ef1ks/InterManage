package com.intermanage.backend.document.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/** Okres raportu przyjęć: dni wg daty bonu, oba końce włącznie. */
public record DeliveryReportPeriodRequest(
        @NotNull(message = "Podaj początek okresu") LocalDate from,
        @NotNull(message = "Podaj koniec okresu") LocalDate to) {
}
