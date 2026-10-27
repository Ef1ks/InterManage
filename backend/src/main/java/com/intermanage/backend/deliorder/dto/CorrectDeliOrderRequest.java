package com.intermanage.backend.deliorder.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Korekta ilości przez dostawcę; 0 = pozycja nie zostanie dostarczona. */
public record CorrectDeliOrderRequest(@NotEmpty List<@Valid Line> items) {

    public record Line(
            @NotNull UUID id,
            @NotNull @DecimalMin(value = "0", message = "Ilość nie może być ujemna") BigDecimal quantity) {
    }
}
