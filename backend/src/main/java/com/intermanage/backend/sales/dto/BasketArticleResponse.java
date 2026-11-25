package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.intermanage.backend.cadtop.dto.CadPrices;

/**
 * Artykuł do koszyka zakupowego z dostawcami z ostatniego roku, od najświeższej dostawy. {@code cadencierCurrent}:
 * aktualna cena z cadencier (tylko artykuł z cadencier), {@code purchasePrice} i {@code caseSize} z kartoteki.
 */
public record BasketArticleResponse(
        String itm8,
        String ean,
        String name,
        String brand,
        String subfamilyCode,
        BigDecimal stock,
        BigDecimal caseSize,
        BigDecimal purchasePrice,
        boolean cadencier,
        CadPrices.Price cadencierCurrent,
        List<Supplier> suppliers) {

    /**
     * Cena zakupu netto sztuki (po korektach) i ilość z ostatniej dostawy, {@code deliveries}: ile ich było w roku,
     * {@code rank}: która z ostatnich dostaw artykułu jest od tego dostawcy (1 = ostatnia, 2 = przedostatnia…).
     */
    public record Supplier(String code, String name, BigDecimal purchasePrice, BigDecimal lastQuantity,
            LocalDateTime lastDate, int deliveries, int rank) {
    }
}
