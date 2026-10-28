package com.intermanage.backend.document.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Zaakceptowane pary przyjęcie PZ + faktura KSeF z {@link AutoInvoiceMatch}. */
public record AutoInvoiceMatchesRequest(@NotEmpty(message = "Zaznacz co najmniej jedno powiązanie") List<@Valid Pair> matches) {

    public record Pair(@NotNull UUID documentId, @NotNull UUID invoiceId) {
    }
}
