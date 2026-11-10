package com.intermanage.backend.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Nazwa dostawcy do kontroli strat i kolor: dowolny {@code #rrggbb} (albo nazwa z dawnej palety, np. {@code sky}). */
public record LossControlGroupRequest(@NotBlank(message = "Podaj nazwę") @Size(max = 80) String name,
        @NotBlank(message = "Wybierz kolor") @Pattern(regexp = "#[0-9a-fA-F]{6}|[a-z]{3,10}", message = "Nieznany kolor") String color) {
}
