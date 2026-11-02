package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.dataentry.entity.SuggestionStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Tabela do pliku (XML faktury albo Excel): wiersze w kolejności z ekranu; {@code suggestionId} wiąże wiersz z
 * propozycją AI. {@code invoiceId} to faktura z KSeF wczytana do tabeli: z niej numer, data i NIP w pliku XML. Bez
 * niej numer, dostawca, NIP i data z dokumentu wczytanego z pliku XML ({@code invoiceNumber}, {@code sellerName},
 * {@code sellerNip}, {@code issueDate}). {@code supplierId} to dostawca z listy sklepu wybrany zamiast sprzedawcy z
 * dokumentu: z niego nazwa, NIP i kod w pliku, a numer dokumentu idzie wtedy z {@code invoiceNumber} (zmiana dostawcy
 * go czyści), także przy fakturze z KSeF.
 */
public record ExportRequest(
        UUID invoiceId,
        @Size(max = 100) String invoiceNumber,
        @Size(max = 300) String sellerName,
        @Size(max = 20) String sellerNip,
        LocalDate issueDate,
        UUID supplierId,
        @NotNull @Size(max = 2000, message = "Najwyżej 2000 wierszy") List<@Valid Row> rows) {

    public record Row(
            UUID suggestionId,
            // Bez EAN-u na fakturze w kolumnie jest inny kod albo nazwa pozycji.
            @Size(max = 300) String ean,
            BigDecimal quantity,
            BigDecimal unitNet,
            @Size(max = 20) String invoiceUnit,
            @Size(max = 500) String name,
            @Size(max = 100) String brand,
            BigDecimal vatPercent,
            BigDecimal salePrice,
            // CENA_CAD artykułu z kartoteki: jest ceną sugerowaną bez liczenia i zaokrąglania.
            BigDecimal cadencierPrice,
            @Size(max = 6) String subfamilyCode,
            @Size(max = 4) String unit,
            BigDecimal netContent,
            SuggestionStatus status,
            boolean known) {
    }
}
