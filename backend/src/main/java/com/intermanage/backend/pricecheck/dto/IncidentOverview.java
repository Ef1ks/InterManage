package com.intermanage.backend.pricecheck.dto;

import java.time.LocalDate;
import java.util.List;

/** Najnowszy dzień z incydentami ({@code null} = nic nie wgrano) i ostatnie wgrania (od najnowszego). */
public record IncidentOverview(LocalDate lastIncidentDate, List<IncidentImportResponse> imports) {
}
