package com.intermanage.backend.ksef.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** IDWew to NIP firmy, myślnik i 5 cyfr, np. 5260001234-00001. */
public record KsefInternalIdRequest(
        @NotBlank(message = "Podaj IDWew")
        @Pattern(regexp = "\\s*\\d{10}-\\d{5}\\s*", message = "IDWew to NIP, myślnik i 5 cyfr, np. 5260001234-00001")
        String internalId,
        @Size(max = 150, message = "Najwyżej 150 znaków") String name) {
}
