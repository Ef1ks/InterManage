package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.sales.component.GratisReportPdf;
import com.intermanage.backend.sales.dto.GratisReportResponse;
import com.intermanage.backend.sales.dto.GratisReportResponse.Figures;
import com.intermanage.backend.sales.dto.GratisReportResponse.Region;
import com.intermanage.backend.sales.dto.GratisReportResponse.Tile;
import com.lowagie.text.pdf.PdfReader;

/** Wydruk raportu gratisów: 6 kafelków na stronie, a gdy kafelki mają dużo rejonów - 4. */
class GratisReportPdfTests {

    private final GratisReportPdf pdf = new GratisReportPdf(new PdfFonts());

    @Test
    void printsSixSmallTilesPerPage() throws IOException {
        // Podsumowanie roku i 10 miesięcy = 11 kafelków: 6 + 5.
        assertThat(pages(report(10, 6))).isEqualTo(2);
    }

    @Test
    void printsFourTilesPerPageWhenTilesAreTall() throws IOException {
        // Podsumowanie i 7 miesięcy z 25 rejonami = 8 kafelków: 4 + 4.
        assertThat(pages(report(7, 25))).isEqualTo(2);
    }

    private int pages(GratisReportResponse report) throws IOException {
        byte[] content = pdf.pdf(report, "Jan Kowalski");
        String out = System.getProperty("gratis.pdf.out");
        if (out != null) {
            Files.write(Path.of(out, "gratisy-" + report.months().size() + ".pdf"), content);
        }
        try (PdfReader reader = new PdfReader(content)) {
            return reader.getNumberOfPages();
        }
    }

    private static GratisReportResponse report(int months, int regions) {
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            YearMonth month = YearMonth.of(2026, i + 1);
            tiles.add(tile(month, month, "Miesiąc " + (i + 1), i % 3 == 2 ? 0 : regions));
        }
        return new GratisReportResponse("10023", "Intermarché Zielona Góra Szosa Kisielińska", YearMonth.of(2026, 1),
                YearMonth.of(2026, months), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26), tiles,
                tile(YearMonth.of(2026, 1), YearMonth.of(2026, months), "Rok 2026", regions));
    }

    private static Tile tile(YearMonth from, YearMonth to, String label, int regions) {
        List<Region> rows = IntStream.range(0, regions)
                .mapToObj(i -> new Region(String.format("%02d", i + 1), "REJON NUMER " + (i + 1) + " ŚWIEŻE",
                        figures()))
                .toList();
        return new Tile(from, to, label, regions, rows, figures());
    }

    private static Figures figures() {
        return new Figures(new BigDecimal("12345.67"), new BigDecimal("2345.10"), new BigDecimal("14690.77"),
                new BigDecimal("1234567.89"), new BigDecimal("1.19"));
    }
}
