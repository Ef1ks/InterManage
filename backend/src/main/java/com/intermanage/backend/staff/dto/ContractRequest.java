package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.EmploymentType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Etat tylko przy umowie o pracę; {@code fixedTermExempt}: wyjątek z art. 25(1) par. 4 KP. */
public record ContractRequest(
        @NotNull(message = "Wybierz rodzaj umowy") ContractKind kind,
        EmploymentType employmentType,
        @NotNull(message = "Podaj początek umowy") LocalDate startDate,
        LocalDate endDate,
        @Min(value = 1, message = "Podaj etat") @Max(value = 100, message = "Podaj etat") int fteNumerator,
        @Min(value = 1, message = "Podaj etat") @Max(value = 100, message = "Podaj etat") int fteDenominator,
        boolean fixedTermExempt) {
}
