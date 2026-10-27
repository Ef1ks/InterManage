package com.intermanage.backend.document.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualInvoiceRequest(
        @NotNull(message = "Wybierz dostawcę") UUID supplierId,
        @NotBlank(message = "Podaj numer faktury") @Size(max = 60, message = "Maksymalnie 60 znaków") String invoiceNumber,
        LocalDate issueDate) {
}
