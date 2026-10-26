package com.intermanage.backend.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "Podaj login")
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$",
                message = "Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik") String username,
        @NotBlank(message = "Podaj imię i nazwisko")
        @Size(max = 100, message = "Maksymalnie 100 znaków") String displayName,
        @NotBlank(message = "Podaj adres e-mail")
        @Email(message = "Podaj poprawny adres e-mail")
        @Size(max = 255, message = "Maksymalnie 255 znaków") String email,
        @NotNull(message = "Wybierz rolę") UUID roleId,
        /** Sklepy, do których pracownik ma dostęp; puste = żaden (właściciel i tak widzi wszystkie). */
        List<UUID> storeIds) {
}
