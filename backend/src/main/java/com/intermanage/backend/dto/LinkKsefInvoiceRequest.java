package com.intermanage.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record LinkKsefInvoiceRequest(@NotNull(message = "Wybierz fakturę") UUID invoiceId) {
}
