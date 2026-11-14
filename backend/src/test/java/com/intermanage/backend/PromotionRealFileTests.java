package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.intermanage.backend.promotion.component.PromotionReportParser;
import com.intermanage.backend.promotion.dto.ParsedPromotionReport;

/**
 * Prawdziwa lista promocji sklepu 10023 (Taryfy.pdf z 14.11.2026). Pliku nie ma w repozytorium, więc test działa
 * tylko tam, gdzie leży na dysku. Nazwa firmy stoi w jednym wierszu z tytułem raportu, a numer sklepu musi się
 * odczytać, bo bez niego plik innego sklepu wgrałby się do wybranego.
 */
class PromotionRealFileTests {

    private static final Path FILE = Path.of("Taryfy.pdf");

    static boolean fileExists() {
        return Files.exists(FILE);
    }

    @Test
    @EnabledIf("fileExists")
    void readsStoreNumberFromHeader() throws Exception {
        ParsedPromotionReport report = new PromotionReportParser().parse(FILE);

        assertThat(report.storeNumber()).isEqualTo("10023");
        assertThat(report.rows()).hasSizeGreaterThan(1000);
    }
}
