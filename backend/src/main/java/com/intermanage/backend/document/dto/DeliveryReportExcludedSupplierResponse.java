package com.intermanage.backend.document.dto;

/** Dostawca pomijany w raporcie przyjęć: kod z linii dokumentów i nazwa w chwili dodania. */
public record DeliveryReportExcludedSupplierResponse(String code, String name) {
}
