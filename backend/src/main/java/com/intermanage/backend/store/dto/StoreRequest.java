package com.intermanage.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StoreRequest(
        @NotBlank(message = "Podaj numer sklepu")
        @Pattern(regexp = "^\\s*\\d{1,20}\\s*$", message = "Numer sklepu to same cyfry, np. 10023") String number,
        @NotBlank(message = "Podaj nazwę sklepu")
        @Size(max = 100, message = "Maksymalnie 100 znaków") String name,
        @Size(max = 255, message = "Maksymalnie 255 znaków") String address,
        /** NIP firmy (opcjonalny, z sumą kontrolną); po nim KSeF łączy sklepy jednej firmy. */
        @Size(max = 20, message = "Nieprawidłowy NIP") String nip,
        boolean active) {
}
