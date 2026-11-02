package com.intermanage.backend.deliproduction.dto;

/** Wynik wgrania bazy z Excela: artykuły dodane, zmienione, bez zmian i nowe schematy. */
public record ProductionImportResponse(int added, int updated, int unchanged, int schemasCreated) {
}
