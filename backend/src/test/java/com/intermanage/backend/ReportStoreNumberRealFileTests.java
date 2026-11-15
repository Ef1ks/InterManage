package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.document.component.DocumentReportParser;
import com.intermanage.backend.document.dto.ParsedDocumentReport;
import com.intermanage.backend.promotion.component.PromotionReportParser;
import com.intermanage.backend.promotion.dto.ParsedPromotionReport;

/**
 * Prawdziwe raporty sklepu 10023 (z 14.11.2026). Plików nie ma w repozytorium, więc testy działają tylko tam, gdzie
 * leżą na dysku. Numer sklepu musi się z nich odczytać, bo import bez niego jest odrzucany: inaczej raport innego
 * sklepu wgrałby się do wybranego.
 */
class ReportStoreNumberRealFileTests {

    private static final Path PROMOTIONS = Path.of("Taryfy.pdf");
    private static final Path DOCUMENTS = Path.of("DOKUMENTY.xlsx");

    static boolean promotionsExist() {
        return Files.exists(PROMOTIONS);
    }

    static boolean documentsExist() {
        return Files.exists(DOCUMENTS);
    }

    @Test
    @EnabledIf("promotionsExist")
    void readsStoreNumberFromPromotionList() throws Exception {
        ParsedPromotionReport report = new PromotionReportParser().parse(PROMOTIONS);

        assertThat(report.storeNumber()).isEqualTo("10023");
        assertThat(report.rows()).hasSizeGreaterThan(1000);
    }

    @Test
    @EnabledIf("documentsExist")
    void readsStoreNumberFromDocumentReport() throws Exception {
        ParsedDocumentReport report = new DocumentReportParser(new XlsxReader()).parse(DOCUMENTS);

        assertThat(report.storeNumber()).isEqualTo("10023");
        assertThat(report.lines()).isNotEmpty();
    }
}
