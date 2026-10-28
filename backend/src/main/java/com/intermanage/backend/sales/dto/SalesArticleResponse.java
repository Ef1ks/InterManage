package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.intermanage.backend.nomenclature.dto.NomenclatureOption;

/**
 * Artykuł z kartoteki sklepu w raporcie sprzedaży: dane, ceny, stan i rotacja z ostatniego raportu kartoteki, w którym
 * był ({@code reportGeneratedAt}), oraz jego rejon, rodzina i podrodzina ({@code nomenclaturePath}, od rejonu).
 */
public record SalesArticleResponse(
        String itm8,
        String ean,
        String name,
        String brand,
        String supplier,
        String unit,
        Boolean active,
        BigDecimal salePrice,
        BigDecimal purchasePrice,
        BigDecimal stock,
        BigDecimal rotation,
        BigDecimal caseSize,
        LocalDateTime reportGeneratedAt,
        List<NomenclatureOption> nomenclaturePath) {
}
