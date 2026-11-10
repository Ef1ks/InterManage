package com.intermanage.backend.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Nazwa dostawcy do kontroli strat i kolor z palety (nazwa koloru, np. {@code red}, {@code sky}). */
public record LossControlGroupRequest(@NotBlank(message = "Podaj nazwę") @Size(max = 80) String name,
        @NotBlank(message = "Wybierz kolor") @Pattern(regexp = "[a-z]{3,10}", message = "Nieznany kolor") String color) {
}
