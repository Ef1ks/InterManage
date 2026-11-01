package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Plik XML faktury z historii "Zapisz XML": opis z nagłówka pliku, kiedy i kto go zapisał. */
public record XmlExportItem(UUID id, String fileName, String documentNumber, LocalDate documentDate,
        String supplierName, String supplierNip, int lineCount, BigDecimal netValue, Instant createdAt,
        String createdBy) {
}
