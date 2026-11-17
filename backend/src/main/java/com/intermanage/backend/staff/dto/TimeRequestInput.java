package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.intermanage.backend.staff.entity.TimeRequestKind;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Żądania wniosków pracowników. */
public final class TimeRequestInput {

    private TimeRequestInput() {
    }

    /**
     * Nowy wniosek: o odcinek karty ({@code start}, {@code end} dokładnie jak odcinek) albo L4 ({@code from},
     * {@code to}).
     */
    public record Create(@NotNull(message = "Wybierz rodzaj wniosku") TimeRequestKind kind, LocalDateTime start,
            LocalDateTime end, LocalDate from, LocalDate to,
            @Size(max = 1000, message = "Uzasadnienie do 1000 znaków") String reason) {
    }

    /** Akceptacja albo odrzucenie z opcjonalną uwagą. */
    public record Step(@Size(max = 500, message = "Uwaga do 500 znaków") String note) {
    }
}
