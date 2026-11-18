package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ile z rocznego celu zysku sklep osiągnął od 1 stycznia ({@code from}) do końca wybranego okresu ({@code to}):
 * marża i marża po stratach z podsumowania za ten czas. {@code target} = {@code null}, gdy rok nie ma celu.
 */
public record ProfitTargetProgressResponse(
        int year,
        LocalDate from,
        LocalDate to,
        ProfitTargetResponse target,
        BigDecimal marginValue,
        BigDecimal marginAfterLossesValue) {
}
