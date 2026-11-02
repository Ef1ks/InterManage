package com.intermanage.backend.meat.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Dokument WZ z formularza. {@code scanId} tylko przy zapisie nowego dokumentu ze skanu; {@code vetNumber},
 * {@code bdo}, {@code nip} to znaki dostawcy odczytane ze skanu, zapamiętywane przy wybranym dostawcy.
 */
public record MeatDeliveryRequest(
        UUID scanId,
        @NotNull(message = "Wybierz dostawcę") UUID supplierId,
        @NotBlank(message = "Wpisz numer dokumentu") @Size(max = 100, message = "Najwyżej 100 znaków")
        String documentNumber,
        @NotNull(message = "Wybierz datę dostawy") LocalDate deliveryDate,
        String vetNumber,
        String bdo,
        String nip,
        @NotEmpty(message = "Dokument nie ma pozycji") List<@Valid Line> lines) {

    /** {@code productId} pusty = pozycja bez artykułu (sam zakup). */
    public record Line(
            @NotBlank(message = "Wpisz nazwę z dokumentu") @Size(max = 300, message = "Najwyżej 300 znaków")
            String documentName,
            @Size(max = 50, message = "Najwyżej 50 znaków") String documentCode,
            @NotNull(message = "Wpisz cenę") @DecimalMin(value = "0.01", message = "Cena musi być większa od zera")
            @Digits(integer = 8, fraction = 4, message = "Nieprawidłowa cena") BigDecimal unitPrice,
            UUID productId,
            Boolean handwrittenCorrection,
            @Size(max = 500, message = "Najwyżej 500 znaków") String note) {

        /** Brak = bez poprawki odręcznej. */
        public boolean corrected() {
            return Boolean.TRUE.equals(handwrittenCorrection);
        }
    }
}
