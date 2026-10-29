package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Ilości, które naprawdę przyjechały, dla linii dokumentu; linia pominięta przyjechała w ilości z dokumentu. */
public record ReceiveTransferRequest(@NotNull @Valid List<Item> items) {

    public record Item(
            @NotNull UUID lineId,
            @NotNull @PositiveOrZero(message = "Ilość nie może być ujemna")
            @DecimalMax(value = "999999.999", message = "Za duża ilość")
            @Digits(integer = 6, fraction = 3, message = "Najwyżej 3 miejsca po przecinku") BigDecimal quantity) {
    }
}
