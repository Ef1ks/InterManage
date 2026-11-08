package com.intermanage.backend.sales.dto;

/**
 * Warunek dostaw raportu ujemnych stanów. {@code NO_DELIVERY_SINCE}: bez dostawy od {@code days} dni (także nigdy),
 * {@code NEVER}: nigdy nie było dostawy, {@code DELIVERED_IN}: była dostawa w okresie od-do, {@code ANY}: bez warunku.
 */
public enum NegativeStockDelivery {
    NO_DELIVERY_SINCE,
    NEVER,
    DELIVERED_IN,
    ANY
}
