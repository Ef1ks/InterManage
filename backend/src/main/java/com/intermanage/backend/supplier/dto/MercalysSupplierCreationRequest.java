package com.intermanage.backend.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Skopiowane makro: NIP, kod, który dostanie dostawca, i jego nazwa. */
public record MercalysSupplierCreationRequest(
        @NotBlank String nip,
        @NotBlank @Pattern(regexp = "\\d{1,8}", message = "Kod to od 1 do 8 cyfr") String code,
        @NotBlank @Size(max = 60) String name) {
}
