package com.intermanage.backend.store.dto;

import java.util.List;

/** Sklepy zaprzyjaźnione i zaproszenia sklepu oraz sklepy, które można jeszcze zaprosić. */
public record StorePartnersResponse(
        List<StorePartnerResponse> partners,
        List<StoreSummary> invitableStores) {
}
