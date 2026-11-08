package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * Parametry raportu ujemnych stanów. {@code delivery} domyślnie {@link NegativeStockDelivery#NO_DELIVERY_SINCE},
 * {@code days} domyślnie 14, {@code from}/{@code to} tylko przy {@link NegativeStockDelivery#DELIVERED_IN}.
 * {@code withProduction}: także rejony produkcji (24, 36, 46), których stany schodzą na minus przez produkcję.
 */
public record NegativeStockRequest(
        NegativeStockDelivery delivery,
        Integer days,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        Boolean withProduction,
        List<String> nomenclatures) {
}
