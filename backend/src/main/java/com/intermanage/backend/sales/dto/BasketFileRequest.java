package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Koszyk zakupowy z porównania sprzedaży do pliku Excel: dostawcy (najwyżej 10 naraz) z artykułami, ilością i ceną
 * zakupu netto z ostatniej dostawy. Jeden dostawca = plik dla niego, kilku = jeden plik z sekcjami.
 */
public record BasketFileRequest(
        @NotNull @Size(min = 1, max = 10, message = "Od 1 do 10 dostawców") List<@Valid Supplier> suppliers) {

    public record Supplier(
            @Size(max = 20) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Size(min = 1, max = 500, message = "Od 1 do 500 artykułów") List<@Valid Line> lines) {
    }

    public record Line(
            @NotBlank @Size(max = 13) String itm8,
            @Size(max = 14) String ean,
            @Size(max = 200) String name,
            @Size(max = 100) String brand,
            @NotNull @DecimalMin(value = "0.001", message = "Ilość musi być większa od zera")
            @DecimalMax("99999") @Digits(integer = 5, fraction = 3) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 9, fraction = 4) BigDecimal purchasePrice) {
    }
}
