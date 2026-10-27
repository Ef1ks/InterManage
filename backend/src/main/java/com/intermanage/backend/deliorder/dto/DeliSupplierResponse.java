package com.intermanage.backend.deliorder.dto;

import com.intermanage.backend.store.dto.StoreSummary;

/** Sklep, z którego formularza można zamawiać. {@code own} = własny formularz sklepu. */
public record DeliSupplierResponse(StoreSummary store, boolean own) {
}
