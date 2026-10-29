package com.intermanage.backend.pricecheck.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Własna kategoria raportu: nazwa i rejony nomenklatury (kody dwucyfrowe, np. "01"). */
public record PriceCheckCategoryRequest(
        @NotBlank(message = "Podaj nazwę kategorii")
        @Size(max = 80, message = "Maksymalnie 80 znaków")
        String name,
        @NotEmpty(message = "Wybierz co najmniej jeden rejon")
        List<@Pattern(regexp = "\\d{2}", message = "Rejon to dwucyfrowy kod nomenklatury") String> regionCodes) {
}
