package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

/** Cel roku: puste pole = bez celu, oba puste usuwają cel. */
public record ProfitTargetRequest(
        @DecimalMin(value = "0.01", message = "Cel musi być większy od zera")
        @DecimalMax(value = "999999999999.99", message = "Za duża kwota")
        @Digits(integer = 12, fraction = 2, message = "Kwota może mieć najwyżej 2 miejsca po przecinku")
        BigDecimal marginValue,
        @DecimalMin(value = "0.01", message = "Cel musi być większy od zera")
        @DecimalMax(value = "999999999999.99", message = "Za duża kwota")
        @Digits(integer = 12, fraction = 2, message = "Kwota może mieć najwyżej 2 miejsca po przecinku")
        BigDecimal marginAfterLossesValue) {
}
