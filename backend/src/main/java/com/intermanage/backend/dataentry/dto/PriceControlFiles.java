package com.intermanage.backend.dataentry.dto;

import java.util.List;

/** Zmiany cen sklepu: wszystkie niewgrane (od najstarszego) i ostatnie wgrane (od najnowszego). */
public record PriceControlFiles(List<PriceControlFileItem> pending, List<PriceControlFileItem> loaded) {
}
