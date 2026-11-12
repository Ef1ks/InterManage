package com.intermanage.backend.staff.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Konto, które dostaje pracownik dotąd bez konta (np. osoba założyła je później). */
public record LinkAccountRequest(@NotNull(message = "Wybierz konto") UUID userId) {
}
