package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.intermanage.backend.stocktake.component.StocktakeAnalyzer;
import com.intermanage.backend.stocktake.component.StocktakeReportParser;
import com.intermanage.backend.stocktake.dto.ParsedStocktakeReport;
import com.intermanage.backend.stocktake.dto.StocktakeReason;

/** Prawdziwy raport z inwentury. Pliku nie ma w repozytorium, więc test działa tylko tam, gdzie leży na dysku. */
class StocktakeRealFileTests {

    private static final Path FILE = Path.of("../plliki2/porownanie po akt stokow.pdf");

    static boolean fileExists() {
        return Files.exists(FILE);
    }

    @Test
    @EnabledIf("fileExists")
    void readsWholeReportAndFindsPairs() throws Exception {
        long start = System.currentTimeMillis();
        ParsedStocktakeReport report = new StocktakeReportParser().parse(FILE);
        long parsed = System.currentTimeMillis();

        assertThat(report.storeNumber()).isEqualTo("10023");
        assertThat(report.stocktakeNumber()).isEqualTo("100");
        assertThat(report.stocktakeName()).isEqualTo("INWENT POLROCZ 2026");
        assertThat(report.wholeStore()).isTrue();
        assertThat(report.rows()).hasSize(19589);
        assertThat(report.differenceValue()).isEqualByComparingTo("44318.89");

        List<StocktakeAnalyzer.Article> articles = report.rows().stream()
                .map(row -> new StocktakeAnalyzer.Article(row.itm8(), row.ean(), row.name(), row.brand(),
                        row.blockType(), row.realQuantity(), row.theoreticalQuantity(), row.differenceQuantity(),
                        row.differenceValue(), row.differenceValue(), row.articleKind(), null, null, BigDecimal.ZERO,
                        BigDecimal.ZERO))
                .toList();
        StocktakeAnalyzer.Result result = new StocktakeAnalyzer().analyze(articles);
        long strong = result.pairs().stream().filter(pair -> pair.strength() == StocktakeReason.SWAP_STRONG).count();
        System.out.printf("STOCKTAKE parse %d ms, analyze %d ms, pairs %d, strong %d, lines %d, counts %s%n",
                parsed - start, System.currentTimeMillis() - parsed, result.pairs().size(), strong, result.lines().size(),
                result.counts());
        assertThat(strong).isGreaterThan(100);
    }
}
