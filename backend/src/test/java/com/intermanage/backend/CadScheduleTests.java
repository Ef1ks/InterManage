package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.cadtop.component.CadSchedule;
import com.intermanage.backend.cadtop.component.CadSchedule.Plan;
import com.intermanage.backend.cadtop.component.CadSchedule.PlanEntry;

/** Kolejność wejść cadencier na wycinku prawdziwych planów 2026 i 2027. */
class CadScheduleTests {

    private static final Plan PLAN_2026 = new Plan(2026, List.of(
            entry("44_2", "2026-10-10"), entry("45", "2026-10-26"), entry("45_1", "2026-11-07"),
            entry(null, "2026-11-14"), entry("46", "2026-11-30"), entry("46_1", "2026-12-05"),
            // Literówka z pliku: w planie 2027 to 46_2.
            entry("47_2", "2026-12-12")));
    private static final Plan PLAN_2027 = new Plan(2027, List.of(
            entry("45", "2026-10-26"), entry("45_1", "2026-11-07"), entry("46", "2026-11-30"),
            entry("46_1", "2026-12-05"), entry("46_2", "2026-12-12"), entry(null, "2027-01-09"),
            entry("46_3", "2027-01-16"), entry("47", "2027-02-01"), entry("47_2", "2027-02-13")));

    @Test
    void newerPlanWinsFromItsFirstDay() {
        CadSchedule schedule = CadSchedule.of(List.of(PLAN_2026, PLAN_2027));

        assertThat(schedule.entries()).extracting(CadSchedule.Entry::nrCad)
                .containsExactly("44_2", "45", "45_1", "46", "46_1", "46_2", "46_3", "47", "47_2");
        assertThat(schedule.find("47_2").orElseThrow().startsOn()).isEqualTo(LocalDate.of(2027, 2, 13));
        assertThat(schedule.find("44_2").orElseThrow().planYear()).isEqualTo(2026);
        assertThat(schedule.find("45").orElseThrow().planYear()).isEqualTo(2027);
    }

    @Test
    void currentIsLatestStartedNumberNotNewestFile() {
        CadSchedule schedule = CadSchedule.of(List.of(PLAN_2027, PLAN_2026));

        // Cad 46 portal wystawił 24.11, ale wchodzi dopiero 30.11.
        assertThat(schedule.currentOn(LocalDate.of(2026, 11, 24)).orElseThrow().nrCad()).isEqualTo("45_1");
        assertThat(schedule.nextAfter(LocalDate.of(2026, 11, 24)).orElseThrow().nrCad()).isEqualTo("46");
        assertThat(schedule.currentOn(LocalDate.of(2026, 11, 29)).orElseThrow().nrCad()).isEqualTo("45_1");
        assertThat(schedule.currentOn(LocalDate.of(2026, 11, 30)).orElseThrow().nrCad()).isEqualTo("46");
        // „Zmiany ręczne” 9.01 nie przerywają 46_2, a koniec to dzień przed kolejnym numerem.
        assertThat(schedule.currentOn(LocalDate.of(2027, 1, 10)).orElseThrow().nrCad()).isEqualTo("46_2");
        assertThat(schedule.find("46_2").orElseThrow().endsOn()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(schedule.entries().getLast().endsOn()).isNull();
        assertThat(schedule.currentOn(LocalDate.of(2026, 1, 1))).isEmpty();
        assertThat(CadSchedule.of(List.of()).currentOn(LocalDate.of(2026, 1, 1))).isEmpty();
    }

    private static PlanEntry entry(String number, String day) {
        return new PlanEntry(number, LocalDate.parse(day), null);
    }
}
