package com.intermanage.backend.deliorder.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/** Nowa data dostawy zamówienia (np. po pomyłce przy składaniu). */
public record ChangeDeliveryDateRequest(@NotNull(message = "Wybierz datę dostawy") LocalDate deliveryDate) {
}
