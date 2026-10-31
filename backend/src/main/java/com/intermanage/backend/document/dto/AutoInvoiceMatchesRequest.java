package com.intermanage.backend.document.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Zaakceptowane pary dokument (przyjęcie PZ albo korekta od dostawcy WZ) + faktura KSeF z {@link AutoInvoiceMatch}.
 * {@code attached}: korekty od dostawcy, które trafiły przy tym do przyjęć (w odpowiedzi na przypięcie; przy cofnięciu
 * odpina je od tych przyjęć).
 */
public record AutoInvoiceMatchesRequest(@NotEmpty(message = "Zaznacz co najmniej jedno powiązanie") List<@Valid Pair> matches) {

    public record Pair(@NotNull UUID documentId, @NotNull UUID invoiceId, List<@Valid CorrectionAttachment> attached) {
    }
}
