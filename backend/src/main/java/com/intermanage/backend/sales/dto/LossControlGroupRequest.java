package com.intermanage.backend.sales.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Nazwa dostawcy do kontroli strat i kolor: dowolny {@code #rrggbb} (albo nazwa z dawnej palety, np. {@code sky}).
 * {@code supplierId}: dostawca z listy dostawców sklepu, którego gratisy za pokrycie strat rozliczają tę grupę;
 * pusty = bez dostawcy.
 */
public record LossControlGroupRequest(@NotBlank(message = "Podaj nazwę") @Size(max = 80) String name,
        @NotBlank(message = "Wybierz kolor") @Pattern(regexp = "#[0-9a-fA-F]{6}|[a-z]{3,10}", message = "Nieznany kolor") String color,
        UUID supplierId) {
}
