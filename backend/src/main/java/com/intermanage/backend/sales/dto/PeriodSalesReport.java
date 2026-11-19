package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sprzedaż za okres po nomenklaturze, tylko z artykułów spełniających filtry. {@code regions}: drzewo rejon → rodzina
 * → podrodzina w kolejności kodów, na końcu „Bez nomenklatury” (kod pusty). Artykuły podrodziny są osobnym
 * zapytaniem. {@code brands}: marki do wyboru w filtrze (po filtrze nomenklatury).
 */
public record PeriodSalesReport(LocalDate from, LocalDate to, Figures total, int articleCount, List<Brand> brands,
        List<Node> regions) {

    /** Marża % od sprzedaży brutto. */
    public record Figures(BigDecimal quantity, BigDecimal costValue, BigDecimal saleValueGross,
            BigDecimal saleValueNet, BigDecimal marginValue, BigDecimal marginPercent) {
    }

    /** {@code level}: REGION, FAMILY albo SUBFAMILY. Podrodzina nie ma {@code children}. */
    public record Node(String code, String name, String level, Figures figures, int articleCount,
            List<Node> children) {
    }

    public record Article(String itm8, String ean, String name, String brand, Figures figures) {
    }

    public record Brand(String name, int articleCount) {
    }
}
