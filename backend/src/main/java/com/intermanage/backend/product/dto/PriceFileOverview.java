package com.intermanage.backend.product.dto;

import java.util.List;

/** Ostatnie pliki TRACEART sklepu (od najnowszego) i liczba artykułów z trwającą promocją z tych plików. */
public record PriceFileOverview(List<PriceFileImportResponse> latest, long activePromotions) {
}
