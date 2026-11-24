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

        // Dziura 1.10-19.11 styka się z bieżącym okresem 20-21.11: jeden okres po miesiącu, ostatni pod ścieżką
        // z ustawień.
        assertThat(result.files()).extracting(ReportSaveMacro.SavedFile::fileName)
                .containsExactly("Sprzedaz 1 z 2.xlsx", "Sprzedaz.xlsx");
        assertThat(result.macro()).contains("^a01102026{Tab}", "^a31102026{Tab}", "^a01112026{Tab}",
                "^a21112026{Tab}", "LABEL|pusty-1-2");
        assertThat(result.macro()).doesNotContain("^a19112026{Tab}", "^a20112026{Tab}");
        assertThat(result.macro()).startsWith("# Intermanage").contains("\r\n" + ReportSaveMacro.OPEN_LINE + "\r\n")
                .endsWith("WINMIN|" + ReportSaveMacro.PROGRAM_WINDOW + "\r\n");
        assertThat(result.playerVersion()).isEqualTo("0.55");
    }

    @Test
    void oneMissingSalesDayBeforeCurrentPeriodIsOneFile() {
        // 10023, 23.11.2026: brakował 21.11, bieżący okres 22-23.11; dawniej dwa pliki.
        Map<String, String> locations = Map.of("import:SALES:path", "P:\\Raporty\\Sprzedaz.xlsx");
        ImportCoverage coverage = new ImportCoverage(
                List.of(new ImportCoverage.DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 20))), List.of());

        ReportSaveMacro.Result result = ReportSaveMacro.build(List.of(ImportReportKind.SALES), locations, coverage,
                LocalDate.of(2026, 11, 23));

        assertThat(result.files()).extracting(ReportSaveMacro.SavedFile::fileName).containsExactly("Sprzedaz.xlsx");
        assertThat(result.macro()).contains("^a21112026{Tab}", "^a23112026{Tab}").doesNotContain("^a22112026{Tab}");
    }

    @Test
    void documentsGapStartsTwoDaysEarlierAndJoinsCurrentPeriod() {
        Map<String, String> locations = Map.of("import:DOCUMENTS:path", "P:\\Raporty\\Dokumenty.xlsx");
        ImportCoverage coverage = new ImportCoverage(List.of(), List.of(
                new ImportCoverage.DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 10))));

        ReportSaveMacro.Result result = ReportSaveMacro.build(List.of(ImportReportKind.DOCUMENTS), locations,
                coverage, TODAY);

        // Dziura 11-18.11 zaczyna się 9.11 i styka się z bieżącym okresem (do jutra): jeden wydruk.
        assertThat(result.files()).hasSize(1);
        assertThat(result.macro()).contains("^a09112026{Tab}", "^a22112026{Tab}");
    }

    @Test
    void documentPartsInterlockAndTypeMaxFirst() {
        Map<String, String> locations = Map.of("import:DOCUMENTS:path", "P:\\Raporty\\Dokumenty.xlsx",
                "import:DOCUMENTS:part", "1m");
        ImportCoverage coverage = new ImportCoverage(List.of(), List.of(
                new ImportCoverage.DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 30))));

        ReportSaveMacro.Result result = ReportSaveMacro.build(List.of(ImportReportKind.DOCUMENTS), locations,
                coverage, TODAY);

        // Dziura od 1.10 zaczyna się 29.09: 29.09-28.10, 28.10-27.11 (cięte do 22.11); data max części staje się
        // datą min kolejnej.
        assertThat(result.files()).hasSize(2);
        String macro = result.macro();
        assertThat(macro).contains("txdZ23_DateMin_TxtBox|^a29092026{Tab}", "txdZ25_DateMax_TxtBox|^a28102026{Tab}",
                "txdZ23_DateMin_TxtBox|^a28102026{Tab}", "txdZ25_DateMax_TxtBox|^a22112026{Tab}");
        // Druga część: formularz zostaje, najpierw data max, potem min.
        String second = macro.substring(macro.indexOf("część 2 z 2"));
        assertThat(second.indexOf("txdZ25_DateMax_TxtBox|^a")).isLessThan(second.indexOf("txdZ23_DateMin_TxtBox|^a"));
        assertThat(macro.split("mnTv_VENTE_ve-e048", -1)).hasSize(2);
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
        assertThat(result.macro()).contains("^a21112028{Tab}", "PDFSAVE|P:\\Raporty\\taryfy.pdf|1800000");
        assertThat(result.playerVersion()).isEqualTo("0.49");
    }
}
