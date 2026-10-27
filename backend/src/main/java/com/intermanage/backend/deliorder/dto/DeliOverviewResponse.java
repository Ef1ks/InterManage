package com.intermanage.backend.deliorder.dto;

import java.util.List;

/**
 * Stan zamówień garmażu sklepu: od kogo może zamawiać, ile nowych zamówień do niego przyszło
 * i jakie zaproszenia czekają na odpowiedź. Służy też do licznika w menu.
 */
public record DeliOverviewResponse(
        List<DeliSupplierResponse> suppliers,
        long newIncomingCount,
        List<DeliInvitationResponse> pendingInvitations) {
}
