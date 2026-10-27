package com.intermanage.backend.deliprice.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * "Odśwież ceny" po podglądzie: {@code calculationId} - jedna kalkulacja, pusty - wszystkie w sklepie.
 * {@code decisions} dla kalkulacji, które w podglądzie miały {@code needsDecision}.
 */
public record RefreshPricesRequest(UUID calculationId, @NotNull List<@Valid Decision> decisions) {

    public record Decision(@NotNull UUID calculationId, @NotNull Choice choice) {
    }

    public enum Choice {
        /** Zostaje cena sprzedaży sprzed odświeżenia (własna; wyliczona zostaje zapisana jako własna). */
        KEEP,
        /** Nowa wyliczona cena; własna cena znika. */
        ACCEPT
    }
}
