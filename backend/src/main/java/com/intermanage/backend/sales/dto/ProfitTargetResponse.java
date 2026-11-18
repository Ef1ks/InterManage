package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Roczny cel zysku sklepu: kwota marży i marży po stratach ({@code null} = bez celu). */
public record ProfitTargetResponse(
        int year,
        BigDecimal marginValue,
        BigDecimal marginAfterLossesValue,
        Instant createdAt,
        String createdByName) {
}
