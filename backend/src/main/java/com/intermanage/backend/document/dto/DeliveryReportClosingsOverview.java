package com.intermanage.backend.document.dto;

import java.util.List;

/**
 * Aktywne zamknięcia raportu przyjęć (od najnowszego okresu), liczba przyjęć zmienionych po zamknięciu i dostawcy
 * pomijani w raporcie.
 */
public record DeliveryReportClosingsOverview(List<DeliveryReportClosingResponse> closings, int pendingChanges,
        List<DeliveryReportExcludedSupplierResponse> excludedSuppliers) {
}
