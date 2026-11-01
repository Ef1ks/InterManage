package com.intermanage.backend.dataentry.dto;

import com.intermanage.backend.dataentry.entity.InvoiceMarkKind;

import jakarta.validation.constraints.NotNull;

public record InvoiceMarkRequest(@NotNull InvoiceMarkKind kind) {
}
