package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.intermanage.backend.nomenclature.dto.NomenclatureOption;

/**
 * Artykuł z kartoteki sklepu w raporcie sprzedaży: dane, ceny, stan i rotacja z ostatniego raportu kartoteki, w którym
 * był ({@code reportGeneratedAt}), oraz jego rejon, rodzina i podrodzina ({@code nomenclaturePath}, od rejonu).
 * {@code vatPercent}: z kodu stawki z kartoteki, bez niego z ostatniego dokumentu artykułu; pusty, gdy nieznany.
 * {@code cadencierPrice}: CENA_CAD z ostatniego raportu kartoteki. {@code promoPrice}, {@code promoFrom}, {@code promoTo}:
 * trwająca dziś promocja z kasy (pliki TRACEART), {@code salePrice} jest wtedy ceną regularną; poza promocją puste.
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
        BigDecimal vatPercent,
        BigDecimal stock,
        BigDecimal rotation,
        BigDecimal caseSize,
        LocalDateTime reportGeneratedAt,
        List<NomenclatureOption> nomenclaturePath,
        boolean cadencier,
        BigDecimal cadencierPrice,
        BigDecimal promoPrice,
        LocalDate promoFrom,
        LocalDate promoTo) {
}
