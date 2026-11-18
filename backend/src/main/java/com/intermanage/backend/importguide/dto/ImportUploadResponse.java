package com.intermanage.backend.importguide.dto;

import java.util.UUID;

/** Założona wysyłka: id i rozmiar kawałka, w którym przeglądarka ma dzielić plik. */
public record ImportUploadResponse(UUID id, int chunkSize) {
}
