package com.intermanage.backend.collector.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Promocje z kolektora z artykułami. Ceny zwykłe i zakupu są z kartoteki z chwili odczytu: {@code regularPrice} brutto
 * bez trwającej promocji, {@code lastPurchasePrice} netto z ostatniego przyjęcia, {@code marginPercent} marża na cenie
 * promocji od brutto, {@code discountPercent} przecena od ceny zwykłej. {@code known}: artykuł jest w kartotece.
 */
public record CollectorPromotionDetails(CollectorPromotionResponse promotion, List<Row> rows) {

    public record Row(String itm8, String ean, String name, String brand, BigDecimal regularPrice, BigDecimal price,
            BigDecimal discountPercent, BigDecimal lastPurchasePrice, BigDecimal marginPercent, LocalDate dateTo,
            boolean known) {
    }
}
