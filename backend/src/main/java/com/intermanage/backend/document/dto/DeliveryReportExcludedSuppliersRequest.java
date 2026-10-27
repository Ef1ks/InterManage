package com.intermanage.backend.document.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Pełna lista kodów dostawców pomijanych w raporcie przyjęć (zastępuje dotychczasową). */
public record DeliveryReportExcludedSuppliersRequest(
        @NotNull(message = "Podaj listę dostawców") List<@NotBlank(message = "Podaj kod dostawcy") String> supplierCodes) {
}
