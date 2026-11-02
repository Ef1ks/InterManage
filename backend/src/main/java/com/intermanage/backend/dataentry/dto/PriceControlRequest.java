package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Artykuły z kartoteki, którym w tabeli zmieniono cenę sprzedaży, do pliku CTRLPRIX. Dostawca (jego hasło idzie do
 * nazwy pliku): wybrany w tabeli ({@code supplierId}), a bez niego z listy sklepu po {@code sellerNip}.
 */
public record PriceControlRequest(
        UUID supplierId,
        @Size(max = 20) String sellerNip,
        @Size(max = 100) String invoiceNumber,
        @NotNull @Size(min = 1, max = 2000, message = "Brak zmienionych cen do pliku") List<@Valid Row> rows) {

    public record Row(@Size(max = 300) String ean, BigDecimal salePrice) {
    }
}
