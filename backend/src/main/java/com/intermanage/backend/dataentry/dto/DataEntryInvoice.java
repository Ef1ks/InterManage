package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Faktura z KSeF wczytana do tabeli: pozycje w kolejności z faktury, bez wierszy "przed korektą". {@code source} to
 * użyte źródło EAN-u, a {@code sources} źródła do wyboru: w ilu pozycjach jest kod i ile z nich jest w kartotece.
 * {@code netTotal} i {@code vatTotal} to sumy z dokumentu (z podsumowania stawek, bez niego z pozycji), z którymi
 * tabela porównuje swoje sumy; {@code null}, gdy faktura ich nie podaje.
 */
public record DataEntryInvoice(UUID invoiceId, String invoiceNumber, String sellerName, String sellerNip,
        LocalDate issueDate, String source, List<CodeSource> sources, List<DataEntryLine> lines,
        BigDecimal netTotal, BigDecimal vatTotal) {

    public record CodeSource(String key, String label, int filled, int matched) {
    }
}
