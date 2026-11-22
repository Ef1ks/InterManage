package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.importguide.component.ReportSaveMacro;
import com.intermanage.backend.importguide.dto.ImportCoverage;
import com.intermanage.backend.importguide.entity.ImportReportKind;

/** Makro zapisu raportów z Mercalys: te same zasady co dawniej we froncie (okresy, części, nazwy plików). */
class ReportSaveMacroTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    @Test
    void salesGapIsPrintedInPartsBeforeCurrentPeriod() {
        Map<String, String> locations = Map.of("import:SALES:path", "P:\\Raporty\\Sprzedaz.xlsx",
                "import:SALES:part", "1m", "import:openedOn", "2026-08-01");
        ImportCoverage coverage = new ImportCoverage(
                List.of(new ImportCoverage.DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30))), List.of());

        ReportSaveMacro.Result result = ReportSaveMacro.build(List.of(ImportReportKind.SALES), locations, coverage,
                TODAY);

        // Dziura 1.10-19.11 po miesiącu (2 części) i bieżący okres 20-21.11: trzy pliki, ostatni pod ścieżką z ustawień.
        assertThat(result.files()).extracting(ReportSaveMacro.SavedFile::fileName)
                .containsExactly("Sprzedaz 1 z 3.xlsx", "Sprzedaz 2 z 3.xlsx", "Sprzedaz.xlsx");
        assertThat(result.macro()).contains("^a01102026{Tab}", "^a31102026{Tab}", "^a01112026{Tab}",
                "^a19112026{Tab}", "^a20112026{Tab}", "^a21112026{Tab}", "LABEL|pusty-1-3");
        assertThat(result.macro()).startsWith("# Intermanage").contains("\r\n" + ReportSaveMacro.OPEN_LINE + "\r\n")
                .endsWith("WINMIN|" + ReportSaveMacro.PROGRAM_WINDOW + "\r\n");
        assertThat(result.playerVersion()).isEqualTo("0.55");
    }

    @Test
    void documentsGapStartsTwoDaysEarlierAndNextPartKeepsForm() {
        Map<String, String> locations = Map.of("import:DOCUMENTS:path", "P:\\Raporty\\Dokumenty.xlsx");
        ImportCoverage coverage = new ImportCoverage(List.of(), List.of(
                new ImportCoverage.DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 10))));

        ReportSaveMacro.Result result = ReportSaveMacro.build(List.of(ImportReportKind.DOCUMENTS), locations,
                coverage, TODAY);

        // Dziura 11-18.11 zaczyna się 9.11, bieżący okres od przedwczoraj do jutra; druga część bez klikania menu.
        assertThat(result.files()).hasSize(2);
        assertThat(result.macro()).contains("^a09112026{Tab}", "^a18112026{Tab}", "^a19112026{Tab}",
                "^a22112026{Tab}");
        assertThat(result.macro().split("mnTv_VENTE_ve-e048", -1)).hasSize(2);
    }

    @Test
    void reportWithoutSettingsIsSkippedWithReason() {
        Map<String, String> locations = Map.of("import:PRODUCTS:path", "P:\\Raporty\\baza.xlsx",
                "import:PROMOTIONS:path", "P:\\Raporty\\taryfy.pdf", "import:CADENCIER:path", "P:\\cad.xlsx",
                "import:CADENCIER:print", "7");

        ReportSaveMacro.Result result = ReportSaveMacro.build(ReportSaveMacro.MACRO_REPORTS, locations,
                new ImportCoverage(List.of(), List.of()), TODAY);

        assertThat(result.reports()).containsExactly(ImportReportKind.PROMOTIONS);
        assertThat(result.skipped()).extracting(ReportSaveMacro.Skipped::kind).containsExactly(
                ImportReportKind.PRODUCTS, ImportReportKind.PRODUCT_CODES, ImportReportKind.CADENCIER,
                ImportReportKind.SALES, ImportReportKind.DOCUMENTS);
        // Lista promocji: data końca dziś + 2 lata, zapis PDF.
        assertThat(result.macro()).contains("^a21112028{Tab}", "PDFSAVE|P:\\Raporty\\taryfy.pdf|600000");
        assertThat(result.playerVersion()).isEqualTo("0.49");
    }
}
