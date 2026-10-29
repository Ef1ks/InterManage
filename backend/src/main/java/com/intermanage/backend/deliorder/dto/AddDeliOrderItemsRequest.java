package com.intermanage.backend.deliorder.dto;

import java.util.List;

import com.intermanage.backend.deliorder.dto.SubmitDeliOrderRequest.Extra;
import com.intermanage.backend.deliorder.dto.SubmitDeliOrderRequest.Line;

import jakarta.validation.Valid;

/**
 * Produkty dopisane do złożonego zamówienia: {@code items} z formularza dostawcy (pozycja w kategorii),
 * {@code extras} z kartoteki dostawcy spoza formularza. Razem co najmniej jedna pozycja.
 */
public record AddDeliOrderItemsRequest(
        List<@Valid Line> items,
        List<@Valid Extra> extras) {

    public List<Line> itemsOrEmpty() {
        return items != null ? items : List.of();
    }

    public List<Extra> extrasOrEmpty() {
        return extras != null ? extras : List.of();
    }
}
