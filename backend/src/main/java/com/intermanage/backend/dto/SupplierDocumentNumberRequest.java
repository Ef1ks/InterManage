package com.intermanage.backend.dto;

import jakarta.validation.constraints.Size;

/** Poprawiony numer dokumentu dostawcy w przyjęciu; pusty = dokument bez numeru. */
public record SupplierDocumentNumberRequest(
        @Size(max = 60, message = "Maksymalnie 60 znaków") String supplierDocumentNumber) {
}
