package com.intermanage.backend.supplier.dto;

import com.intermanage.backend.supplier.entity.SupplierContactKind;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nowy albo zmieniony kontakt u dostawcy; wystarczy jedno z: imię i nazwisko, telefon, e-mail. */
public record SupplierContactRequest(
        @NotNull(message = "Wybierz, kto to jest") SupplierContactKind kind,
        @Size(max = 150, message = "Maksymalnie 150 znaków") String name,
        @Size(max = 100, message = "Maksymalnie 100 znaków") String jobTitle,
        @Size(max = 50, message = "Maksymalnie 50 znaków") String phone,
        @Email(message = "To nie wygląda na adres e-mail")
        @Size(max = 200, message = "Maksymalnie 200 znaków") String email,
        @Size(max = 2000, message = "Maksymalnie 2000 znaków") String notes) {
}
