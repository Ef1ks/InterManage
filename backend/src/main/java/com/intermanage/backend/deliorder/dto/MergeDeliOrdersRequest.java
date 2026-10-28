package com.intermanage.backend.deliorder.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Zamówienia jednego sklepu na ten sam dzień dostawy do połączenia w jedno. */
public record MergeDeliOrdersRequest(
        @NotNull @Size(min = 2, message = "Wybierz co najmniej dwa zamówienia") List<@NotNull UUID> orderIds) {
}
