package com.intermanage.backend.auth.dto;

import com.intermanage.backend.auth.security.AutoLogout;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Własne automatyczne wylogowywanie; puste minuty zostawiają zapisany czas. */
public record AutoLogoutRequest(
        @NotNull(message = "Podaj, czy wylogowywać automatycznie") Boolean enabled,
        @Min(value = AutoLogout.MIN_MINUTES, message = "Najkrócej 5 minut")
        @Max(value = AutoLogout.MAX_MINUTES, message = "Najdłużej 8 godzin") Integer minutes,
        @NotNull(message = "Podaj, czy wylogowywać po pracy") Boolean workdayLock) {
}
