package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.staff.dto.TimeCardResponse;
import com.intermanage.backend.staff.service.StaffTimeSheetService;

/** Które dni karty ewidencji i eksporty liczą: wcześniejsze zawsze, dzisiejszy dopiero po zmianie i wyjściu. */
class TimeSheetFinishedDayTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @Test
    void todayCountsAfterShiftWithClosedPunches() {
        assertThat(StaffTimeSheetService.finished(day(TODAY, 15, null), TODAY.atTime(15, 30))).isTrue();
    }

    @Test
    void todayWaitsForEndOfShiftAndExit() {
        assertThat(StaffTimeSheetService.finished(day(TODAY, 15, null), TODAY.atTime(14, 0))).isFalse();
        assertThat(StaffTimeSheetService.finished(day(TODAY, 15, TODAY.atTime(7, 0)), TODAY.atTime(16, 0)))
                .isFalse();
    }

    @Test
    void pastDaysAlwaysCountAndFutureNever() {
        assertThat(StaffTimeSheetService.finished(day(TODAY.minusDays(1), 15, TODAY.minusDays(1).atTime(7, 0)),
                TODAY.atTime(1, 0))).isTrue();
        assertThat(StaffTimeSheetService.finished(day(TODAY.plusDays(1), 15, null), TODAY.atTime(23, 0))).isFalse();
    }

    private static TimeCardResponse.DayCard day(LocalDate date, int endHour, LocalDateTime openSince) {
        return new TimeCardResponse.DayCard(date, date.atTime(7, 0), date.atTime(endHour, 0), null, List.of(),
                openSince, false, List.of(), 480, 480, 0, 0, 0, null, null, null);
    }
}
