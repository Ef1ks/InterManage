package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Przyjęcie PZ po korektach: linie przyjęcia z dodanymi liniami korekt o tym samym ITM8 (ilości i kwoty w korektach
 * są ze znakiem) i artykuły, które dopisała sama korekta. Z tego powstaje też wydruk bonu dostawy.
 */
public record CorrectedDelivery(BigDecimal purchaseValue, BigDecimal saleValueGross, List<CorrectedLine> lines) {

    /**
     * Linia po korektach. {@code key}: id linii przyjęcia albo (artykuł dopisany korektą) linii korekty.
     * {@code originalQuantity} i {@code originalPurchaseValue} to wartości z przyjęcia ({@code null}: artykułu nie
     * było w przyjęciu); {@code corrected}, gdy którąś linię zmieniła korekta.
     */
    public record CorrectedLine(
            String key,
            int lineNumber,
            String itm8,
            String ean,
            String name,
            String brand,
            String unit,
            String nomenclatureCode,
            BigDecimal quantity,
            BigDecimal purchasePrice,
            BigDecimal salePriceDocument,
            BigDecimal purchaseValue,
            BigDecimal saleValueGross,
            BigDecimal saleValueNet,
            Short vatRate,
            BigDecimal vatValue,
            BigDecimal marginValue,
            BigDecimal marginPercent,
            BigDecimal originalQuantity,
            BigDecimal originalPurchaseValue,
            boolean corrected) {
    }
}
