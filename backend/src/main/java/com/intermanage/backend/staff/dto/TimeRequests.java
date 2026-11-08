package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.intermanage.backend.staff.entity.TimeDecisionKind;
import com.intermanage.backend.staff.entity.TimeSegmentKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Żądania kart czasu pracy. */
public final class TimeRequests {

    private TimeRequests() {
    }

    /** Decyzja o odcinku wskazanym godzinami od-do (czas lokalny), jak w karcie. */
    public record Decision(@NotNull UUID employeeId, @NotNull TimeSegmentKind kind, @NotNull LocalDateTime start,
            @NotNull LocalDateTime end, @NotNull TimeDecisionKind decision,
            @Size(max = 500, message = "Uwaga do 500 znaków") String note) {
    }

    /** Odbicie z symulatora: bez godziny = teraz. */
    public record SimulatedPunch(@NotNull UUID employeeId, LocalDateTime at) {
    }

    /** Odbicie z czytnika: numer karty i czas (bez czasu = chwila odebrania). */
    public record ReaderPunch(@NotBlank(message = "Brak numeru karty") @Size(max = 64) String cardUid,
            Instant punchedAt) {
    }

    public record Reader(@NotBlank(message = "Podaj nazwę czytnika") @Size(max = 100) String name) {
    }

    public record Card(@NotBlank(message = "Podaj numer karty") @Size(max = 64, message = "Numer karty do 64 znaków")
    String cardUid) {
    }

    public record Employer(@NotBlank(message = "Podaj nazwę pracodawcy") @Size(max = 200) String name,
            @Size(max = 300) String address, @Size(max = 10) String nip, @Size(max = 14) String regon,
            @Size(max = 200) String representative) {
    }
}
