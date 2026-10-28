package com.intermanage.backend.pricecheck.dto;

import com.intermanage.backend.pricecheck.entity.PriceCheckStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nowy status wyjaśniania przyjęcia z notatką (np. co odpowiedział dostawca). */
public record PriceCheckReviewRequest(
        @NotNull(message = "Wybierz status") PriceCheckStatus status,
        @Size(max = 1000, message = "Notatka może mieć najwyżej 1000 znaków") String note) {
}
