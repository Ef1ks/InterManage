package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code username} pusty = pierwsza litera imienia i nazwisko, z numerem, gdy taki login jest już zajęty. */
public record CreateEmployeeRequest(
        @NotBlank(message = "Podaj imię i nazwisko")
        @Size(max = 100, message = "Maksymalnie 100 znaków") String displayName,
        @NotBlank(message = "Podaj adres e-mail")
        @Email(message = "Podaj poprawny adres e-mail")
        @Size(max = 255, message = "Maksymalnie 255 znaków") String email,
        @Pattern(regexp = "^$|^[A-Za-z0-9._-]{3,50}$",
                message = "Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik") String username,
        @NotNull(message = "Wybierz stanowisko") UUID positionId,
        @NotNull @Size(max = 50, message = "Maksymalnie 50 godzin pracy") List<@Valid ShiftRequest> shifts) {
}
