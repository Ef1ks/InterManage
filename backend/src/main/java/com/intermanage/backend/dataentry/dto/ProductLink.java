package com.intermanage.backend.dataentry.dto;

import java.util.UUID;

/** Zapisane przypięcie: {@code code} po normalizacji, {@code product} artykuł z kartoteki do wiersza tabeli. */
public record ProductLink(UUID id, String code, CatalogProduct product) {
}
