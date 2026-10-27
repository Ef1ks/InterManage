package com.intermanage.backend.dto;

/** Sklep, z którego formularza można zamawiać. {@code own} = własny formularz sklepu. */
public record DeliSupplierResponse(StoreSummary store, boolean own) {
}
