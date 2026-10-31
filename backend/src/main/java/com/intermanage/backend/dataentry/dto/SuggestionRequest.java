package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Nowe artykuły do uzupełnienia: {@code key} to identyfikator wiersza w tabeli, wraca w odpowiedzi.
 * {@code sellerNip} i {@code sellerName} z faktury (dostawca podpowiada podrodziny), {@code examples} to znane
 * artykuły z tej samej faktury (jak dostawca nazywa to, co sklep już ma).
 */
public record SuggestionRequest(
        UUID invoiceId,
        String sellerNip,
        String sellerName,
        @Size(max = 20, message = "Najwyżej 20 przykładów") List<@Valid Example> examples,
        @NotEmpty(message = "Brak pozycji do uzupełnienia")
        @Size(max = 200, message = "Najwyżej 200 pozycji naraz") List<@Valid Item> items) {

    public record Item(
            @NotBlank String key,
            @Size(max = 14) String ean,
            @NotBlank(message = "Pozycja bez nazwy") @Size(max = 500) String invoiceName,
            @Size(max = 20) String invoiceUnit) {
    }

    public record Example(@NotBlank @Size(max = 500) String invoiceName, @NotBlank @Size(max = 200) String catalogName,
            @Size(max = 100) String brand) {
    }
}
