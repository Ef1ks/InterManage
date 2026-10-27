package com.intermanage.backend.ksef.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Dostawca do pominięcia po NIP (myślniki i spacje są usuwane); nazwa tylko do wyświetlania. */
public record KsefSkippedSupplierRequest(
        @NotBlank(message = "Podaj NIP dostawcy") String nip,
        @Size(max = 512, message = "Najwyżej 512 znaków") String name) {
}
