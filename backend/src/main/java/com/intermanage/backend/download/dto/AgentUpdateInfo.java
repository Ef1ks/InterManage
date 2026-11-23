package com.intermanage.backend.download.dto;

/**
 * Obowiązująca wersja agenta sklepu z „Do pobrania” (paczka agenta): agent porównuje {@code version} ze swoją,
 * pobiera plik z {@code /api/store-agent/update/file} i sprawdza {@code sha256} (hex) oraz rozmiar.
 */
public record AgentUpdateInfo(String version, String sha256, int sizeBytes) {
}
