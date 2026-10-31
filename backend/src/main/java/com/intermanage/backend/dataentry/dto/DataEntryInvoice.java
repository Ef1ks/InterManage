package com.intermanage.backend.dataentry.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Faktura z KSeF wczytana do tabeli: pozycje w kolejności z faktury, bez wierszy "przed korektą". */
public record DataEntryInvoice(UUID invoiceId, String invoiceNumber, String sellerName, String sellerNip,
        LocalDate issueDate, List<DataEntryLine> lines) {
}
