package com.intermanage.backend.staff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Nowe konto dla pracownika dotąd bez konta: imię i nazwisko bierze z pracownika, a link aktywacyjny idzie na e-mail.
 * {@code username} pusty = login złożony z imienia i nazwiska.
 */
public record InviteAccountRequest(
        @NotBlank(message = "Podaj adres e-mail")
        @Email(message = "Podaj poprawny adres e-mail")
        @Size(max = 255, message = "Maksymalnie 255 znaków") String email,
        @Pattern(regexp = "^$|^[A-Za-z0-9._-]{3,50}$",
                message = "Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik") String username) {
}
