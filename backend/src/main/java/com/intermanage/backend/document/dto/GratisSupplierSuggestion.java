package com.intermanage.backend.document.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dostawca, który ostatnio przywoził artykuły z dokumentu (z przyjęć PZ). Przy bazie (538, 88110) {@code brand} to marka
 * artykułów, bo gratis daje producent, a nie baza. {@code supplierId} pusty, gdy dostawcy nie ma na liście sklepu.
 */
public record GratisSupplierSuggestion(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String brand,
        LocalDateTime lastDeliveryDate,
        long productCount) {
}
