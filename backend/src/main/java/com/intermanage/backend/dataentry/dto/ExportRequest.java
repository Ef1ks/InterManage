package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.dataentry.entity.SuggestionStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Tabela do pliku Excel: wiersze w kolejności z ekranu; {@code suggestionId} wiąże wiersz z propozycją AI. */
public record ExportRequest(
        @Size(max = 100) String invoiceNumber,
        @Size(max = 300) String sellerName,
        @NotNull @Size(max = 2000, message = "Najwyżej 2000 wierszy") List<@Valid Row> rows) {

    public record Row(
            UUID suggestionId,
            @Size(max = 14) String ean,
            BigDecimal quantity,
            BigDecimal unitNet,
            @Size(max = 20) String invoiceUnit,
            @Size(max = 500) String name,
            @Size(max = 100) String brand,
            BigDecimal vatPercent,
            BigDecimal salePrice,
            @Size(max = 6) String subfamilyCode,
            @Size(max = 4) String unit,
            BigDecimal netContent,
            SuggestionStatus status,
            boolean known) {
    }
}
