package com.intermanage.backend.document.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record LinkKsefInvoiceRequest(@NotNull(message = "Wybierz fakturę") UUID invoiceId) {
}
