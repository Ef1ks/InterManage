package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Nowe i zmienione artykuły z tabeli do pliku CADBEZP dla programu sklepu. Dostawca i numer idą tylko do nazwy pliku:
 * dostawca wybrany w tabeli ({@code supplierId}), dostawca z listy sklepu po {@code sellerNip}, a bez nich
 * {@code sellerName}. W wierszu {@code purchasePrice} to cena zakupu netto sztuki sprzedaży, a {@code pack} sztuk
 * w kartonie (PAK z dokumentu dostawy z bazy) do Opak.Bazy i Zamówienia minimalnego; bez niego 1.
 */
public record ProductFileRequest(
        UUID supplierId,
        @Size(max = 20) String sellerNip,
        @Size(max = 300) String sellerName,
        @Size(max = 100) String invoiceNumber,
        @NotNull @Size(min = 1, max = 2000, message = "Brak artykułów do pliku") List<@Valid Row> rows) {

    public record Row(
            @Size(max = 300) String ean,
            @Size(max = 500) String name,
            @Size(max = 100) String brand,
            @Size(max = 4) String unit,
            BigDecimal netContent,
            BigDecimal purchasePrice,
            BigDecimal vatPercent,
            BigDecimal salePrice,
            @Size(max = 6) String subfamilyCode,
            @Size(max = 50) String supplierCode,
            @Size(max = 20) String cn,
            BigDecimal pack) {
    }
}
