package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.document.component.DocumentPrint;
import com.intermanage.backend.document.component.DocumentPrint.Detail;
import com.intermanage.backend.document.component.DocumentPrint.Heading;
import com.intermanage.backend.document.component.DocumentPrint.Line;
import com.intermanage.backend.document.component.DocumentPrint.NomenclatureRow;
import com.intermanage.backend.document.component.DocumentPrint.Party;
import com.intermanage.backend.document.component.DocumentPrintPdf;
import com.lowagie.text.pdf.PdfReader;

/** Wydruk dokumentu: podsumowania z linii i PDF, także przy dokumencie na wiele stron. */
class DocumentPrintTests {

    private static final Map<String, String> NAMES = Map.of("14", "OWOCE I WARZYWA", "1401", "OWOCE", "1402", "WARZYWA",
            "31", "PIEKARNIA", "3110", "PIECZYWO");

    private final DocumentPrintPdf pdf = new DocumentPrintPdf(new PdfFonts());

    @Test
    void summarizesRegionsAndFamiliesWithSharesOfPurchase() {
        DocumentPrint print = print(List.of(line("140102", "60", "90", 23), line("140201", "20", "30", 5),
                line("311002", "20", "30", 5)));

        assertThat(print.nomenclature()).extracting(NomenclatureRow::code)
                .containsExactly("14", "1401", "1402", "31", "3110");
        NomenclatureRow region = print.nomenclature().getFirst();
        assertThat(region.region()).isTrue();
        assertThat(region.name()).isEqualTo("OWOCE I WARZYWA");
        assertThat(region.lineCount()).isEqualTo(2);
        assertThat(region.purchaseValue()).isEqualByComparingTo("80");
        assertThat(region.share()).isEqualByComparingTo("80");
        assertThat(print.nomenclature().get(1).region()).isFalse();
        assertThat(print.totals().purchaseValue()).isEqualByComparingTo("100");
    }

    @Test
    void linesWithoutNomenclatureAreLastAndEmptyLinesAreSkipped() {
        DocumentPrint print = print(List.of(line(null, "5", "9", 23), line("140102", "1", "2", 23),
                line("140102", "0", "0", 23, "0")));

        assertThat(print.lines()).hasSize(2);
        assertThat(print.skippedLines()).isEqualTo(1);
        assertThat(print.nomenclature()).extracting(NomenclatureRow::name)
                .containsExactly("OWOCE I WARZYWA", "OWOCE", "Bez nomenklatury");
    }

    @Test
    void valueWithoutQuantityStaysOnThePrint() {
        // Korekta od dostawcy to zwykle obniżka ceny z ilością 0.
        DocumentPrint print = print(List.of(line("140102", "-12.50", "0", 23, "0")));

        assertThat(print.lines()).hasSize(1);
        assertThat(print.totals().purchaseValue()).isEqualByComparingTo("-12.50");
    }

    @Test
    void mixedSignsHaveNoShares() {
        // Przeniesienie stanów: ten sam towar wychodzi z jednego rejonu i wchodzi do drugiego.
        DocumentPrint print = print(List.of(line("140102", "-10", "-20", 5), line("311002", "10", "20", 5)));

        assertThat(print.nomenclature()).allSatisfy(row -> assertThat(row.share()).isNull());
    }

    @Test
    void vatRatesGoFromHighestWithPurchaseVatFromTheRateSum() {
        DocumentPrint print = print(List.of(line("140102", "10.10", "20", 5), line("140102", "10.10", "20", 5),
                line("311002", "100", "150", 23)));

        assertThat(print.vatRates()).hasSize(2);
        assertThat(print.vatRates().getFirst().rate()).isEqualTo((short) 23);
        assertThat(print.vatRates().getFirst().purchaseVat()).isEqualByComparingTo("23.00");
        assertThat(print.vatRates().get(1).purchaseVat()).isEqualByComparingTo("1.01");
        assertThat(print.totals().purchaseVat()).isEqualByComparingTo("24.01");
    }

    @Test
    void longDocumentGoesOntoManyPages() throws IOException {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 293; i++) {
            lines.add(line(i % 3 == 0 ? "140102" : i % 3 == 1 ? "140201" : "311002", "12.34", "19.99", i % 2 == 0 ? 5 : 23));
        }
        assertThat(pages(print(lines), "dlugi")).isGreaterThan(5);
    }

    @Test
    void shortDocumentFitsOnOnePage() throws IOException {
        assertThat(pages(print(List.of(line("140102", "60", "90", 23), line("311002", "20", "30", 5))), "krotki"))
                .isEqualTo(1);
    }

    private int pages(DocumentPrint print, String name) throws IOException {
        byte[] content = pdf.pdf(print);
        String out = System.getProperty("document.print.out");
        if (out != null) {
            Files.write(Path.of(out, "wydruk-" + name + ".pdf"), content);
        }
        try (PdfReader reader = new PdfReader(content)) {
            return reader.getNumberOfPages();
        }
    }

    private static DocumentPrint print(List<Line> lines) {
        Heading heading = new Heading("PROTOKÓŁ LIKWIDACJI", "Likwidacja", "PL1000120261102",
                LocalDate.of(2026, 11, 2), new Party(null, "10023 · Zielona Góra",
                        List.of("ul. Szosa Kisielińska 22", "NIP 7812022538")), null,
                List.of(new Detail("Nr dok. dostawcy", "F/1/26")),
                List.of("Sporządził(a)", "Członek komisji", "Zatwierdził(a)"));
        return DocumentPrint.of(heading, lines, NAMES, "Jan Kowalski");
    }

    private static Line line(String nomenclature, String purchase, String saleGross, int vat) {
        return line(nomenclature, purchase, saleGross, vat, "2");
    }

    private static Line line(String nomenclature, String purchase, String saleGross, int vat, String quantity) {
        BigDecimal gross = new BigDecimal(saleGross);
        return new Line(1, "0000061515426", "5902180810132", "ŚLIWKI WĘGIERKI LUZ", "SONKO", "SCAPR POLSKA SP.Z.O.O.",
                nomenclature, "kg", new BigDecimal(quantity), (short) vat, new BigDecimal("4.20"), new BigDecimal(purchase),
                new BigDecimal("6.99"), gross, BigDecimal.ZERO, gross, null);
    }
}
