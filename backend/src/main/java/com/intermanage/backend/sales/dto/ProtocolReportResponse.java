package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.store.entity.ProtocolKind;

/**
 * Raport protokołu za okres, opcjonalnie z okresem porównywanym ({@code previousFrom}/{@code previousTo} i
 * {@code previous} w liczbach puste bez porównania). Grupy od największej wartości zakupu; przy grupowaniu po
 * artykule grupa jest artykułem, a {@code articles} ma tylko jego.
 */
public record ProtocolReportResponse(ProtocolKind protocol, ProtocolReportGrouping grouping, NomenclatureLevel level,
        LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, LocalDate documentsCoveredUntil,
        Figures total, Figures previousTotal, List<Group> groups) {

    /** Ilość, wartość zakupu netto (bez minusa wydania) i wartość w cenach sprzedaży brutto. */
    public record Figures(BigDecimal quantity, BigDecimal purchaseValue, BigDecimal saleValueGross) {
    }

    /** {@code key} jest stały między okresami (kod, ITM8, marka); {@code code} pokazuje się przy nazwie. */
    public record Group(String key, String code, String name, Figures current, Figures previous,
            List<Article> articles) {
    }

    public record Article(String itm8, String name, String ean, String brand, Figures current, Figures previous) {
    }
}
