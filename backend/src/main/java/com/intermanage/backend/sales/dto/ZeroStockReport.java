package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.intermanage.backend.nomenclature.dto.NomenclatureOption;

/**
 * Raport zerówek: artykuły aktywne w cadencier ze stanem 0 w wybranych pozycjach nomenklatury, po rodzinach.
 * {@code stockReportGeneratedAt} to data raportu kartoteki, z którego są stany, {@code cadencierGeneratedAt} data
 * listy cadencier (pusta = sklep nie ma listy, więc raport nie ma z czego się policzyć).
 */
public record ZeroStockReport(
        String storeNumber,
        String storeName,
        LocalDateTime stockReportGeneratedAt,
        LocalDateTime cadencierGeneratedAt,
        List<NomenclatureOption> selection,
        int itemCount,
        List<Group> groups) {

    public record Group(String code, String name, List<Item> items) {
    }

    public record Item(String itm8, String ean, String name, String brand, BigDecimal stock) {
    }
}
