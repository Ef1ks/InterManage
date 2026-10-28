package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.sales.component.PeriodSummaryPdf;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.component.RegionGroups.Group;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Figures;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Kind;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Line;
import com.lowagie.text.pdf.PdfReader;

/** Wydruk podsumowania sprzedaży i strat zawsze mieści się na jednej kartce A4. */
class PeriodSummaryPdfTests {

    private final PeriodSummaryPdf pdf = new PeriodSummaryPdf(new PdfFonts());

    @Test
    void fitsOnePageWithAllRegions() throws IOException {
        assertThat(pages(summary(RegionGroups.GROUPS, 0))).isEqualTo(1);
    }

    @Test
    void shrinksToOnePageWhenThereAreManyRegions() throws IOException {
        assertThat(pages(summary(RegionGroups.GROUPS, 60))).isEqualTo(1);
    }

    private int pages(PeriodSummaryResponse summary) throws IOException {
        try (PdfReader reader = new PdfReader(pdf.pdf(summary, "Jan Kowalski"))) {
            return reader.getNumberOfPages();
        }
    }

    /** Wszystkie rejony z grup z dużymi kwotami i budżetem, do tego {@code extra} rejonów w "Pozostałe". */
    private static PeriodSummaryResponse summary(List<Group> groups, int extra) {
        List<Line> lines = new ArrayList<>();
        for (Group group : groups) {
            add(lines, group, 0, extra);
        }
        return new PeriodSummaryResponse("10023", "Intermarché Zielona Góra Szosa Kisielińska",
                LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), null, null, true, lines, figures());
    }

    private static void add(List<Line> lines, Group group, int depth, int extra) {
        lines.add(new Line(Kind.GROUP, depth, null, group.name(), null, null, null));
        if (group.leaf()) {
            List<String> codes = new ArrayList<>(group.regions());
            if (group.rest()) {
                IntStream.range(0, extra).forEach(i -> codes.add("R" + i));
            }
            for (String code : codes) {
                lines.add(new Line(Kind.REGION, depth + 1, code, "Bardzo długa nazwa rejonu nomenklatury " + code,
                        figures(), new BigDecimal("25"), new BigDecimal("1.5")));
            }
        } else {
            for (Group child : group.children()) {
                add(lines, child, depth + 1, extra);
            }
        }
        lines.add(new Line(Kind.SUBTOTAL, depth, null, "Razem " + group.name().toLowerCase(), figures(), null,
                null));
    }

    private static Figures figures() {
        return new Figures(new BigDecimal("12345678.90"), new BigDecimal("13333333.33"),
                new BigDecimal("9876543.21"), new BigDecimal("2469135.69"), new BigDecimal("20.0"),
                new BigDecimal("-123456.78"), new BigDecimal("-0.9"), new BigDecimal("2345678.91"),
                new BigDecimal("19.0"), new BigDecimal("100.0"));
    }
}
