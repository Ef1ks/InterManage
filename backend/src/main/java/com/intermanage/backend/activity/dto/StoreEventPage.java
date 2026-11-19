package com.intermanage.backend.activity.dto;

import java.util.List;

/** Porcja dziennika od najnowszych; kolejną pobiera się od czasu i id ostatniego wpisu. */
public record StoreEventPage(List<StoreEventResponse> items, boolean hasMore) {
}
