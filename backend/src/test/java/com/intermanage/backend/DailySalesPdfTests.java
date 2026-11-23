package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.sales.component.DailySalesPdf;
import com.intermanage.backend.sales.dto.DailySalesReport;
import com.intermanage.backend.sales.dto.DailySalesReport.Figures;
import com.intermanage.backend.sales.dto.DailySalesReport.Line;
import com.intermanage.backend.sales.dto.DailySalesRequest.Layout;
import com.intermanage.backend.sales.dto.DailySalesRequest.Level;
import com.intermanage.backend.sales.dto.DailySalesRequest.Measure;
import com.lowagie.text.pdf.PdfReader;

/**
 * Wydruk sprzedaży dziennej: miesiąc rejonów mieści się na jednej kartce w obu układach. Próbki zapisuje
 * {@code -DargLine=-Ddaily.pdf.out=<folder>}.
 */
class DailySalesPdfTests {

    private final DailySalesPdf pdf = new DailySalesPdf(new PdfFonts());

    @Test
    void monthOfRegionsFitsOnePageWithDaysAsRows() throws IOException {
        assertThat(pages(report(31, 28, 0), Level.REGION, Layout.DAYS_AS_ROWS, "wiersze")).isEqualTo(1);
    }

    @Test
    void monthOfRegionsFitsOnePageWithDaysAsColumns() throws IOException {
        assertThat(pages(report(31, 28, 0), Level.REGION, Layout.DAYS_AS_COLUMNS, "kolumny")).isEqualTo(1);
    }

    @Test
    void familiesPrintOnMorePages() throws IOException {
        assertThat(pages(report(31, 28, 5), Level.FAMILY, Layout.DAYS_AS_COLUMNS, "rodziny")).isGreaterThan(1);
        assertThat(pages(report(31, 28, 5), Level.FAMILY, Layout.DAYS_AS_ROWS, "rodziny-wiersze")).isPositive();
    }

    private int pages(DailySalesReport report, Level level, Layout layout, String name) throws IOException {
        byte[] content = pdf.pdf("10023", "Intermarché Zielona Góra", report, level, Measure.GROSS, layout, false,
                "Jan Kowalski");
        String out = System.getProperty("daily.pdf.out");
        if (out != null) {
            Files.write(Path.of(out, "sprzedaz-dzienna-" + name + ".pdf"), content);
        }
        try (PdfReader reader = new PdfReader(content)) {
            return reader.getNumberOfPages();
        }
    }

    private static DailySalesReport report(int dayCount, int regionCount, int familyCount) {
        LocalDate from = LocalDate.of(2026, 10, 1);
        List<LocalDate> days = from.datesUntil(from.plusDays(dayCount)).toList();
        List<Line> regions = new ArrayList<>();
        for (int region = 1; region <= regionCount; region++) {
            String code = String.format("%02d", region);
            List<Line> families = new ArrayList<>();
            for (int family = 1; family <= familyCount; family++) {
                families.add(line(code + String.format("%02d", family), "RODZINA " + family, "FAMILY", days.size(),
                        6_000 / region));
            }
            // Jak w sklepie: kilka dużych rejonów, reszta mała; razem ok. 120 tys. zł dziennie.
            regions.add(line(code, region + "_REJON NUMER " + region, "REGION", days.size(), 30_000 / region));
            regions.set(regions.size() - 1, new Line(code, region + "_REJON NUMER " + region, "REGION",
                    regions.getLast().days(), regions.getLast().sum(), families));
        }
        Line total = line("", null, "TOTAL", days.size(), 120_000);
        return new DailySalesReport(from, days.getLast(), days, days.getLast(), total, regions);
    }

    private static Line line(String code, String name, String level, int days, int gross) {
        List<Figures> cells = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            cells.add(figures(gross + day * 17));
        }
        return new Line(code, name, level, cells, figures(gross * days), List.of());
    }

    private static Figures figures(int gross) {
        BigDecimal value = BigDecimal.valueOf(gross).add(new BigDecimal("0.45"));
        return new Figures(BigDecimal.valueOf(gross / 10), value, value.multiply(new BigDecimal("0.9")),
                value.multiply(new BigDecimal("0.2")));
    }
}
