package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.entity.CalculationMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Kalkulacja z kreatora dla gotowego wyrobu {@code productId} z kartoteki. {@code batchYield} (kg wyrobu z przepisu)
 * jest wymagane przy {@code PER_BATCH}. Ilości w jednostce produktu z kartoteki (kg, L, szt.). {@code vatRate}
 * w procentach: 0, 5, 8 albo 23.
 */
public record PriceCalculationRequest(
        @NotNull(message = "Wybierz wyrób z kartoteki") UUID productId,
        @NotNull(message = "Wybierz, jak wpisujesz skład") CalculationMode mode,
        @DecimalMin(value = "0", inclusive = false, message = "Podaj, ile kg wychodzi z przepisu")
        @Digits(integer = 9, fraction = 3, message = "Najwyżej 3 miejsca po przecinku") BigDecimal batchYield,
        @NotNull(message = "Wybierz stawkę VAT") Short vatRate,
        /** Własna cena sprzedaży 1 kg zamiast wyliczonej; {@code null} = cena wyliczona. */
        @DecimalMin(value = "0", inclusive = false, message = "Cena musi być większa od zera")
        @Digits(integer = 8, fraction = 2, message = "Najwyżej 2 miejsca po przecinku") BigDecimal manualPrice,
        @NotNull @Size(max = 200, message = "Najwyżej 200 składników") List<@Valid Item> items) {

    public record Item(
            @NotNull UUID productId,
            @NotNull(message = "Podaj ilość")
            @DecimalMin(value = "0", inclusive = false, message = "Ilość musi być większa od zera")
            @Digits(integer = 9, fraction = 3, message = "Najwyżej 3 miejsca po przecinku") BigDecimal quantity,
            /**
             * Średnia waga 1 sztuki w kg dla składnika liczonego na sztuki; zapamiętywana dla produktu w sklepie,
             * {@code null} ją czyści. Przy innych jednostkach pomijana.
             */
            @DecimalMin(value = "0", inclusive = false, message = "Waga sztuki musi być większa od zera")
            @Digits(integer = 7, fraction = 3, message = "Najwyżej 3 miejsca po przecinku") BigDecimal pieceWeight) {
    }
}
