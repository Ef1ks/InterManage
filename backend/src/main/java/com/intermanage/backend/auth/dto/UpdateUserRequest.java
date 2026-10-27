package com.intermanage.backend.auth.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code newPassword} puste = bez zmiany hasła. Ustawione hasło jest tymczasowe - użytkownik musi je zmienić. */
public record UpdateUserRequest(
        @NotBlank(message = "Podaj imię i nazwisko")
        @Size(max = 100, message = "Maksymalnie 100 znaków") String displayName,
        @Email(message = "Podaj poprawny adres e-mail")
        @Size(max = 255, message = "Maksymalnie 255 znaków") String email,
        @NotNull(message = "Wybierz rolę") UUID roleId,
        boolean enabled,
        @Size(min = 8, max = 72, message = "Hasło musi mieć od 8 do 72 znaków") String newPassword,
        /** {@code null} = bez zmian, pusta lista = odbiera dostęp do wszystkich sklepów. */
        List<UUID> storeIds) {
}
