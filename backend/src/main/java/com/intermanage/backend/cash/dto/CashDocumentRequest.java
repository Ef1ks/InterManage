package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.intermanage.backend.cash.entity.CashDocumentKind;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nowy dokument KP albo KW. „Za co” = operacja z listy i opis; kontrahent i adres są opcjonalne. */
public record CashDocumentRequest(
        @NotNull(message = "Wybierz rodzaj dokumentu") CashDocumentKind kind,
        @NotNull(message = "Podaj datę") LocalDate issueDate,
        @NotNull(message = "Podaj kwotę")
        @DecimalMin(value = "0.01", message = "Kwota musi być większa od zera")
        @DecimalMax(value = "9999999999.99", message = "Za duża kwota")
        @Digits(integer = 10, fraction = 2, message = "Kwota z najwyżej dwoma miejscami po przecinku") BigDecimal amount,
        @NotBlank(message = "Wybierz albo wpisz operację") @Size(max = 100, message = "Najwyżej 100 znaków") String operation,
        @Size(max = 500, message = "Najwyżej 500 znaków") String title,
        @Size(max = 200, message = "Najwyżej 200 znaków") String counterparty,
        @Size(max = 300, message = "Najwyżej 300 znaków") String counterpartyAddress) {
}
