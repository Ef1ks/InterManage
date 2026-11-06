package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kafelek WZ. {@code dateEstimated}: data wyliczona z okresu faktury, bo dostawca podaje same numery;
 * {@code sourceInvoice}: numer faktury, z której jest kafelek ({@code null} = dodany ręcznie).
 */
public record BakeryWzResponse(UUID id, String number, LocalDate deliveryDate, boolean dateEstimated,
        String sourceInvoice, UUID scanId, String scanFileName, String scanContentType, List<Line> lines) {

    public record Line(BakeryArticle article, BigDecimal quantity, BigDecimal scannedQuantity, boolean manual) {
    }
}
