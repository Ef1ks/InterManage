package com.intermanage.backend.dataentry.dto;

/**
 * Plik XML faktury (z dysku, z historii albo zapis tymczasowy) do wczytania do tabeli: pozycje jak z faktury KSeF
 * ({@code document}, {@code invoiceId} = faktura z KSeF tego dostawcy o tym numerze, gdy jest widoczna w sklepie).
 * {@code recipientCode} to odbiorca z pliku, a {@code otherStore} = inny niż numer wybranego sklepu. {@code draft} to
 * tabela z przeglądarki zapisana razem z zapisem tymczasowym (JSON), żeby wróciła dokładnie taka, jak była.
 */
public record XmlDocument(String fileName, String recipientCode, boolean otherStore, DataEntryInvoice document,
        String draft) {
}
