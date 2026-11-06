package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Zapis kafelka: zastępuje jego ilości. Pusta ilość albo 0 usuwa wiersz, chyba że artykuł dopisano ręcznie. */
public record BakeryWzRequest(@Size(max = 100, message = "Numer WZ ma najwyżej 100 znaków") String number,
        LocalDate deliveryDate, @NotNull List<@Valid Line> lines) {

    public record Line(@NotNull UUID productId,
            @PositiveOrZero(message = "Ilość nie może być ujemna")
            @Digits(integer = 9, fraction = 3, message = "Ilość ma najwyżej 3 miejsca po przecinku") BigDecimal quantity,
            BigDecimal scannedQuantity, boolean manual) {
    }
}
