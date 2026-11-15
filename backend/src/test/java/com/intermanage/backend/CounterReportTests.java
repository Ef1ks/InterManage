package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.product.dto.ArticleLinks;
import com.intermanage.backend.product.entity.ArticleStoreLink;
import com.intermanage.backend.sales.component.CounterReportPdf;
import com.intermanage.backend.sales.dto.CounterReport;
import com.intermanage.backend.sales.dto.CounterReport.Basis;
import com.intermanage.backend.sales.dto.CounterReport.Metric;
import com.intermanage.backend.sales.repository.SaleRepository.ArticleSales;
import com.intermanage.backend.sales.service.CounterReportService;
import com.lowagie.text.pdf.PdfReader;

/** Raport lady: drzewo rejon â†’ rodzina â†’ podrodzina â†’ artykuĹ‚ w kilku sklepach i podziaĹ‚ wydruku na tabele. */
class CounterReportTests {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 2);
    private static final LocalDate TO = LocalDate.of(2026, 11, 8);

    @Test
    void sumsEveryLevelPerStore() {
        CounterReport report = CounterReportService.build(FROM, TO, List.of("24", "36"),
                Map.of("36", "GARMAZERKA", "3660", "DANIA GOTOWE TRAD", "366003", "SALATKI"), stores(2),
                List.of(List.of(sale("0000000000001", "366003", 10, 50, 100, 95.24, 45.24),
                        sale("0000000000002", "366004", 5, 20, 40, 38.10, 18.10),
                        sale("0000000000003", "240201", 2, 30, 42, 40, 10)),
                        List.of(sale("0000000000001", "366003", 4, 20, 42, 40, 20))), ArticleLinks.none());

        assertThat(report.regions()).extracting(CounterReport.Region::code).containsExactly("24", "36");
        CounterReport.Region deli = report.regions().get(1);
        assertThat(deli.name()).isEqualTo("GARMAZERKA");
        assertThat(deli.values().get(0).saleValueGross()).isEqualByComparingTo("140");
        assertThat(deli.values().get(1).saleValueGross()).isEqualByComparingTo("42");
        // UdziaĹ‚ w sprzedaĹĽy wybranych rejonĂłw sklepu: 140 z 182.
        assertThat(deli.values().get(0).sharePercent()).isEqualByComparingTo("76.92");

        CounterReport.Subfamily salads = deli.families().getFirst().subfamilies().getFirst();
        assertThat(salads.name()).isEqualTo("SALATKI");
        // MarĹĽa od sprzedaĹĽy brutto: 20 z 42.
        assertThat(salads.values().get(1).marginPercent()).isEqualByComparingTo("47.62");
        // SurĂłwek drugi sklep nie sprzedaĹ‚.
        assertThat(deli.families().getFirst().subfamilies().get(1).values().get(1)).isNull();
        assertThat(report.total().get(1).saleValueGross()).isEqualByComparingTo("42");
    }

    @Test
    void linkedArticlesOfOtherStoreShareOneRow() {
        List<CounterReport.Store> stores = stores(2);
        UUID own = stores.get(0).id();
        UUID other = stores.get(1).id();
        // Ten sam boczek: u nas 99002446, u sąsiada lokalny 99021281 w innej podrodzinie.
        ArticleLinks links = ArticleLinks.of(List.of(new ArticleStoreLink(other, "0000099021281", own,
                "0000099002446", null)), List.of(own, other));
        CounterReport report = CounterReportService.build(FROM, TO, List.of("24"), Map.of(), stores,
                List.of(List.of(sale("0000099002446", "240301", 64, 1116, 989, 942, -174)),
                        List.of(sale("0000099021281", "240302", 19, 327, 441, 420, 93))),
                links);

        List<CounterReport.Subfamily> subfamilies = report.regions().getFirst().families().getFirst().subfamilies();
        assertThat(subfamilies).extracting(CounterReport.Subfamily::code).containsExactly("240301");
        CounterReport.Article article = subfamilies.getFirst().articles().getFirst();
        assertThat(subfamilies.getFirst().articles()).hasSize(1);
        assertThat(article.itm8()).isEqualTo("0000099002446");
        assertThat(article.values().get(1).saleValueGross()).isEqualByComparingTo("441");
        assertThat(article.sources().get(1).itm8()).isEqualTo("0000099021281");
        assertThat(article.sources().get(1).name()).isEqualTo("ARTYKUL 0000099021281");
    }

    @Test
    void marginIsAfterLiquidations() {
        List<CounterReport.Store> stores = stores(1);
        // Sprzedaż: marża 45,24 zł z 100 zł brutto; likwidacje: 10 zł tego artykułu i 5 zł artykułu bez sprzedaży.
        CounterReport report = CounterReportService.build(FROM, TO, List.of("36"), Map.of(), stores,
                List.of(List.of(sale("0000000000001", "366003", 10, 50, 100, 95.24, 45.24))),
                List.of(List.<DocumentLineRepository.ArticleLoss>of(new Loss("0000000000001", "366003", "SALATKA", null, new BigDecimal("10")),
                        new Loss("0000000000009", "366003", "SURÓWKA", null, new BigDecimal("5")))),
                ArticleLinks.none());

        CounterReport.Figures total = report.total().getFirst();
        assertThat(total.lossValue()).isEqualByComparingTo("15");
        assertThat(total.marginValue()).isEqualByComparingTo("30.24");
        assertThat(total.marginPercent()).isEqualByComparingTo("30.24");
        List<CounterReport.Article> articles = report.regions().getFirst().families().getFirst().subfamilies()
                .getFirst().articles();
        assertThat(articles).hasSize(2);
        CounterReport.Article liquidated = articles.stream().filter(item -> item.itm8().endsWith("9")).findFirst()
                .orElseThrow();
        assertThat(liquidated.values().getFirst().marginValue()).isEqualByComparingTo("-5");
        assertThat(liquidated.values().getFirst().marginPercent()).isNull();
    }

    private record Loss(String getItm8, String getSubfamilyCode, String getName, String getBrand,
            BigDecimal getLossValue) implements DocumentLineRepository.ArticleLoss {
    }

    @Test
    void splitsMetricsEvenlyWhenColumnsDoNotFit() {
        List<Metric> five = List.of(Metric.QUANTITY, Metric.COST, Metric.SALE, Metric.MARGIN, Metric.MARGIN_PERCENT);
        assertThat(CounterReportPdf.split(five, 3)).hasSize(1);
        assertThat(CounterReportPdf.split(five, 6)).extracting(List::size).containsExactly(3, 2);
        List<Metric> six = List.of(Metric.QUANTITY, Metric.COST, Metric.SALE, Metric.MARGIN, Metric.MARGIN_PERCENT,
                Metric.SHARE);
        assertThat(CounterReportPdf.split(six, 6)).extracting(List::size).containsExactly(3, 3);
    }

    @Test
    void printsSixStoresWithArticles() throws IOException {
        List<List<ArticleSales>> perStore = new ArrayList<>();
        for (int store = 0; store < 6; store++) {
            List<ArticleSales> sales = new ArrayList<>();
            for (int article = 0; article < 120; article++) {
                String subfamily = List.of("240201", "366001", "366003", "460101").get(article % 4);
                sales.add(sale(String.format("%013d", article), subfamily, 12.5 + store, 120 + article,
                        250 + article, 238 + article, 118 + store));
            }
            perStore.add(sales);
        }
        CounterReport report = CounterReportService.build(FROM, TO, List.of("24", "36", "46"), Map.of(),
                stores(6), perStore, ArticleLinks.none());
        byte[] content = new CounterReportPdf(new PdfFonts()).pdf(report, List.of(Metric.values()), Basis.GROSS,
                true, "Jan Kowalski");
        try (PdfReader reader = new PdfReader(content)) {
            assertThat(reader.getNumberOfPages()).isGreaterThan(1);
        }
    }

    private static List<CounterReport.Store> stores(int count) {
        List<CounterReport.Store> stores = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stores.add(new CounterReport.Store(UUID.randomUUID(), String.valueOf(10023 + i),
                    "Zielona GĂłra - Sklep " + i, TO));
        }
        return stores;
    }

    private static ArticleSales sale(String itm8, String subfamily, double quantity, double cost, double gross,
            double net, double margin) {
        return new Sale(itm8, subfamily, "ARTYKUL " + itm8, null, "MARKA", BigDecimal.valueOf(quantity),
                BigDecimal.valueOf(cost), BigDecimal.valueOf(gross), BigDecimal.valueOf(net),
                BigDecimal.valueOf(margin));
    }

    private record Sale(String getItm8, String getSubfamilyCode, String getName, String getEan, String getBrand,
            BigDecimal getQuantity, BigDecimal getCostValue, BigDecimal getSaleValueGross,
            BigDecimal getSaleValueNet, BigDecimal getMarginValue) implements ArticleSales {
    }
}
