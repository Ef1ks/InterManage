package com.intermanage.backend.pricecheck.dto;

import java.util.List;
import java.util.UUID;

/** Własna kategoria raportu zakupu bazowego poza bazą. */
public record PriceCheckCategoryResponse(UUID id, String name, List<String> regionCodes) {
}
