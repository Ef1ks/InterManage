package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.cadtop.component.CadPlanParser;
import com.intermanage.backend.cadtop.component.CadSchedule;
import com.intermanage.backend.cadtop.component.CadTopParser;
import com.intermanage.backend.common.component.XlsxReader;

/** Prawdziwe plany cadencier i pliki TOP, gdy leżą na dysku (skoroszyty i {@code emea/} są poza repozytorium). */
class CadTopRealFileTests {

    private static final Path PLAN_2026 = Path.of("..", "cad_planing_2026_pdv_-_aktualizacja_z_dnia_12.04.2026.xlsx");
    private static final Path PLAN_2027 = Path.of("..", "cad_planing_2027_pdv.xlsx");
    private static final List<Path> TOP_FOLDERS = List.of(Path.of("..", "emea", "emea", "api_cad_top"),
            Path.of("..", "emea", "emea", "Dane", "17_topy xl co cadencier"));

    @Test
    void readsBothPlans() throws Exception {
        assumeTrue(Files.exists(PLAN_2026) && Files.exists(PLAN_2027), "brak planów cadencier");
        CadPlanParser parser = new CadPlanParser(new XlsxReader());

        CadPlanParser.Parsed older = parser.parse(PLAN_2026);
        CadPlanParser.Parsed newer = parser.parse(PLAN_2027);

        assertThat(older.year()).isEqualTo(2026);
        assertThat(newer.year()).isEqualTo(2027);
        assertThat(older.entries()).hasSize(40);
        assertThat(newer.entries()).hasSize(40);
        assertThat(older.modifiedAt()).isBefore(newer.modifiedAt());
        assertThat(older.entries().getFirst().nrCad()).isEqualTo("35");
        assertThat(older.entries().getFirst().startsOn()).isEqualTo(LocalDate.of(2025, 10, 27));
        assertThat(newer.entries().getLast().endsOn()).isNull();

        CadSchedule schedule = CadSchedule.of(List.of(plan(older), plan(newer)));
        assertThat(schedule.currentOn(LocalDate.of(2026, 11, 24)).orElseThrow().nrCad()).isEqualTo("45_1");
        assertThat(schedule.currentOn(LocalDate.of(2026, 11, 30)).orElseThrow().nrCad()).isEqualTo("46");
        assertThat(schedule.currentOn(LocalDate.of(2027, 1, 20)).orElseThrow().nrCad()).isEqualTo("46_3");
        assertThat(schedule.currentOn(LocalDate.of(2026, 7, 1)).orElseThrow().nrCad()).isEqualTo("41");
    }

    private static CadSchedule.Plan plan(CadPlanParser.Parsed parsed) {
        return new CadSchedule.Plan(parsed.year(), parsed.entries().stream()
                .map(entry -> new CadSchedule.PlanEntry(entry.nrCad(), entry.startsOn(), entry.comment())).toList());
    }

    @Test
    void readsTopFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        for (Path folder : TOP_FOLDERS) {
            if (Files.isDirectory(folder)) {
                try (Stream<Path> list = Files.list(folder)) {
                    list.filter(file -> file.toString().endsWith(".xlsx")).forEach(files::add);
                }
            }
        }
        assumeTrue(!files.isEmpty(), "brak plików TOP");
        for (Path file : files) {
            CadTopParser.Parsed parsed = new CadTopParser(new XlsxReader()).parse(file);

            assertThat(parsed.nrCad()).as(file.toString()).matches("\\d+(_\\d+)?");
            assertThat(parsed.articles()).as(file.toString()).hasSizeGreaterThan(13_000).allSatisfy(article -> {
                assertThat(article.itm8()).hasSize(13);
                assertThat(article.ean()).hasSize(13);
                assertThat(article.salePrice()).isPositive();
                assertThat(article.subfamilyCode()).hasSize(6);
                assertThat(article.topMark()).isIn(null, "BTop", "B", "A");
            });
        }
        CadTopParser.Article first = new CadTopParser(new XlsxReader()).parse(files.getFirst()).articles().getFirst();
        assertThat(first.itm8()).isEqualTo("0000000000453");
        assertThat(first.ean()).isEqualTo("5900983014894");
        assertThat(first.name()).isEqualTo("SERNIK BLYSKAWICZ.ORYGIN.154G");
        assertThat(first.purchasePrice()).isEqualByComparingTo("5.45");
        assertThat(first.salePrice()).isEqualByComparingTo("6.49");
        assertThat(first.marginPercent()).isEqualByComparingTo("8.62");
        assertThat(first.networkRotation()).isEqualByComparingTo("1.13");
        assertThat(first.isNew()).isFalse();
    }
}
