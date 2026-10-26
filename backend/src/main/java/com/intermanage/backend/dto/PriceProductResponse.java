package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.intermanage.backend.entity.PriceSource;

/**
 * Produkt z kartoteki jako składnik: {@code unit} to jednostka, w której wpisuje się ilość, a {@code unitCost} cena
 * zakupu tej jednostki - z ostatniej dostawy (przyjęcie PZ {@code priceDocumentNumber} z {@code priceDate}) albo
 * z kartoteki ({@code priceSource}). W wynikach wyszukiwania to cena aktualna, a {@code quantity}, {@code cost}
 * i {@code pricedAt} są puste; w kalkulacji to cena zapamiętana {@code pricedAt}. {@code pieceWeight} to zapamiętana
 * średnia waga 1 sztuki w kg (tylko składniki liczone na sztuki) - z nią sztuki wliczają się do wagi surowców.
 */
public record PriceProductResponse(
        UUID productId,
        String itm8,
        String ean,
        String name,
        String brand,
        String unit,
        BigDecimal pieceWeight,
        BigDecimal unitCost,
        PriceSource priceSource,
        UUID priceDocumentId,
        String priceDocumentNumber,
        LocalDateTime priceDate,
        Boolean active,
        BigDecimal quantity,
        BigDecimal cost,
        Instant pricedAt) {
}
