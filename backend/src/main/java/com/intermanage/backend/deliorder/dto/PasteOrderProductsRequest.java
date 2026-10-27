package com.intermanage.backend.deliorder.dto;

import java.util.List;

import com.intermanage.backend.deliorder.entity.OrderUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Lista produktów wklejona z Excela do kategorii formularza: EAN i jednostka zamawiania. */
public record PasteOrderProductsRequest(
        @NotEmpty(message = "Wklej co najmniej jeden produkt")
        @Size(max = 2000, message = "Naraz można wkleić najwyżej 2000 produktów")
        List<@Valid Row> rows) {

    public record Row(
            @NotBlank(message = "Podaj EAN") @Size(max = 32, message = "EAN jest za długi") String ean,
            @NotNull(message = "Wybierz jednostkę") OrderUnit orderUnit) {
    }
}
