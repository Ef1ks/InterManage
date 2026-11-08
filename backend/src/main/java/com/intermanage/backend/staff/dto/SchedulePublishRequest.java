package com.intermanage.backend.staff.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SchedulePublishRequest(@NotNull UUID departmentId,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Miesiąc w formacie RRRR-MM") String month) {
}
