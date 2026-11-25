package com.intermanage.backend.collector.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Zawartość jednej albo kilku list razem, artykuł raz (ilości zsumowane). Ceny są z kartoteki z chwili odczytu:
 * {@code purchasePrice} netto, {@code salePrice} brutto na kasie (w promocji promocyjna), {@code marginPercent} od brutto.
 */
public record CollectorListDetails(List<CollectorListResponse> lists, List<Row> rows, BigDecimal totalQuantity,
        BigDecimal totalPurchaseValue, BigDecimal totalSaleValue) {

    /**
     * Artykuł. {@code scans}: z czego powstała ilość, gdy skanowano inny kod niż główny albo kilka razy (np. 8 sztuk
     * kodu puszki = 2 czteropaki). {@code known}: artykuł jest w kartotece sklepu.
     */
    public record Row(String itm8, String ean, String name, String brand, BigDecimal purchasePrice,
            BigDecimal salePrice, BigDecimal saleValue, BigDecimal marginPercent, BigDecimal quantity,
            List<Scan> scans, boolean known) {
    }

    public record Scan(String code, BigDecimal entered, BigDecimal quantity) {
    }
}
