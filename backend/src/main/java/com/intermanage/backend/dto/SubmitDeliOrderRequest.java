package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.entity.OrderUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Zamówienie z formularza dostawcy. {@code items} to pozycje formularza (produkt w kategorii), {@code extras} to
 * dopisane przez sklep produkty z kartoteki dostawcy spoza formularza. Razem co najmniej jedna pozycja.
 */
public record SubmitDeliOrderRequest(
        @NotNull(message = "Wybierz datę dostawy") LocalDate deliveryDate,
        List<@Valid Line> items,
        List<@Valid Extra> extras) {

    public record Line(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "Ilość musi być większa od zera")
            BigDecimal quantity) {
    }

    /** {@code sectionId} to dział formularza dostawcy, do którego trafia pozycja (kartka idzie do działu). */
    public record Extra(
            @NotNull UUID productId,
            @NotNull(message = "Wybierz dział") UUID sectionId,
            @NotNull(message = "Wybierz jednostkę") OrderUnit unit,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "Ilość musi być większa od zera")
            BigDecimal quantity) {
    }

    public List<Line> itemsOrEmpty() {
        return items != null ? items : List.of();
    }

    public List<Extra> extrasOrEmpty() {
        return extras != null ? extras : List.of();
    }
}
