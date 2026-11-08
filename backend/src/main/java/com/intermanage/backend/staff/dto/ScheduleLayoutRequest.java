package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cały układ grafiku działu: pracownicy bez grupy i grupy, każde w kolejności wyświetlania. Zastępuje poprzedni. Grupa bez
 * {@code id} jest nowa, a brakująca dostaje znacznik. {@code month}: miesiąc, który ma wrócić w odpowiedzi.
 */
public record ScheduleLayoutRequest(@NotNull UUID departmentId,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Miesiąc w formacie RRRR-MM") String month,
        @NotNull List<UUID> ungrouped, @NotNull List<@Valid Group> groups) {

    public record Group(UUID id, @NotBlank(message = "Podaj nazwę grupy")
            @Size(max = 60, message = "Nazwa grupy do 60 znaków") String name, @NotNull List<UUID> employeeIds) {
    }
}
