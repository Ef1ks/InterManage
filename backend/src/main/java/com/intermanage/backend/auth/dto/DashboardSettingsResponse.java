package com.intermanage.backend.auth.dto;

import com.intermanage.backend.auth.entity.DashboardChartMetric;

import jakarta.validation.constraints.NotNull;

/** Ustawienia pulpitu konta; ten sam kształt w odpowiedzi i w zapisie. */
public record DashboardSettingsResponse(@NotNull(message = "Wybierz, co ma pokazywać wykres") DashboardChartMetric salesChartMetric) {
}
