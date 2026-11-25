package com.intermanage.backend.ksef.dto;

import jakarta.validation.constraints.NotNull;

public record KsefSupplierAccessRequest(@NotNull Boolean allSuppliers) {
}
