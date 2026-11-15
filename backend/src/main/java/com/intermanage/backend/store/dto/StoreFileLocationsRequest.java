package com.intermanage.backend.store.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Zmienione ścieżki sklepu: klucz → wartość. Klucze spoza żądania zostają bez zmian, pusta wartość (albo null) czyści
 * ścieżkę.
 */
public record StoreFileLocationsRequest(
        @NotNull @Size(max = 100, message = "Najwyżej 100 ścieżek naraz") Map<
                @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}", message = "Nieznany rodzaj ścieżki") String,
                @Size(max = 500, message = "Najwyżej 500 znaków") String> locations) {
}
