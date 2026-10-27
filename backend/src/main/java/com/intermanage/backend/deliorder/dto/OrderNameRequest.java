package com.intermanage.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nazwa działu albo kategorii formularza zamówienia. */
public record OrderNameRequest(
        @NotBlank(message = "Podaj nazwę")
        @Size(max = 100, message = "Maksymalnie 100 znaków") String name) {
}
