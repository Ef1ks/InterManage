package com.intermanage.backend.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Dostawca spoza listy z systemu sklepu, dodawany ręcznie. Adres opcjonalny (np. z „Pobierz z GUS”). */
public record CreateSupplierRequest(
        @NotBlank(message = "Podaj nazwę dostawcy")
        @Size(max = 200, message = "Maksymalnie 200 znaków") String name,
        @Pattern(regexp = "^[\\d\\s-]{0,20}$", message = "NIP to same cyfry, np. 9291243024") String nip,
        @Size(max = 255, message = "Maksymalnie 255 znaków") String address,
        @Size(max = 10, message = "Maksymalnie 10 znaków") String postalCode,
        @Size(max = 100, message = "Maksymalnie 100 znaków") String city) {
}
