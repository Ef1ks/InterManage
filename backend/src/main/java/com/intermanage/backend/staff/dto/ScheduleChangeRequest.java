package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.staff.entity.ScheduleDayOffKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Zmiany komórek grafiku. {@code month}: miesiąc, który ma wrócić w odpowiedzi. */
public record ScheduleChangeRequest(@NotNull UUID departmentId,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Miesiąc w formacie RRRR-MM") String month,
        @NotNull @Size(min = 1, max = 2000, message = "Podaj od 1 do 2000 zmian") List<@Valid Change> changes) {

    /**
     * Puste godziny i {@code dayOff} czyszczą dzień. {@code storeId}: inny sklep firmy, gdy pracownik jest tam tylko tego
     * dnia. {@code dayOff}: dzień wolny zamiast zmiany, przy WN z {@code forDate}, czyli za którą niedzielę albo święto.
     * {@code civil}: godziny na umowie zlecenia (osobny wiersz), bez dni wolnych. {@code positionId}: stanowisko tego
     * dnia, gdy inne niż stałe (z listy stanowisk pracownika); puste = stałe.
     */
    public record Change(@NotNull UUID employeeId, @NotNull LocalDate date,
            @Pattern(regexp = "\\d{2}:\\d{2}", message = "Godzina w formacie GG:MM") String start,
            @Pattern(regexp = "\\d{2}:\\d{2}", message = "Godzina w formacie GG:MM") String end, UUID storeId,
            ScheduleDayOffKind dayOff, LocalDate forDate, Boolean civil, UUID positionId) {

        public boolean isCivil() {
            return Boolean.TRUE.equals(civil);
        }

        public Change withPosition(UUID position) {
            return new Change(employeeId, date, start, end, storeId, dayOff, forDate, civil, position);
        }
    }
}
