package com.intermanage.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Dostawca spoza listy z systemu sklepu, dodawany ręcznie. */
public record CreateSupplierRequest(
        @NotBlank(message = "Podaj nazwę dostawcy")
        @Size(max = 200, message = "Maksymalnie 200 znaków") String name,
        @Pattern(regexp = "^[\\d\\s-]{0,20}$", message = "NIP to same cyfry, np. 9291243024") String nip) {
}
