package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.staff.component.TimeRounding;

class TimeRoundingTests {

    private static LocalDateTime at(String time) {
        return LocalDateTime.parse("2026-12-01T" + time);
    }

    @Test
    void entryGoesUpAndExitGoesDown() {
        assertThat(TimeRounding.up(at("07:52:30"), 15)).isEqualTo(at("08:00"));
        assertThat(TimeRounding.up(at("08:00:40"), 15)).isEqualTo(at("08:00"));
        assertThat(TimeRounding.up(at("08:01"), 15)).isEqualTo(at("08:15"));
        assertThat(TimeRounding.down(at("16:07:59"), 15)).isEqualTo(at("16:00"));
        assertThat(TimeRounding.down(at("16:15"), 15)).isEqualTo(at("16:15"));
        assertThat(TimeRounding.up(at("23:55"), 10)).isEqualTo(LocalDateTime.parse("2026-12-02T00:00"));
    }

    @Test
    void zeroOnlyTruncatesToMinutes() {
        assertThat(TimeRounding.up(at("07:52:30"), 0)).isEqualTo(at("07:52"));
        assertThat(TimeRounding.down(at("07:52:30"), 0)).isEqualTo(at("07:52"));
    }

    @Test
    void eachStoreUsesItsOwnRules() {
        UUID rounded = UUID.randomUUID();
        TimeRounding rounding = new TimeRounding(Map.of(rounded, 30));
        assertThat(rounding.entry(rounded, at("06:05"))).isEqualTo(at("06:30"));
        assertThat(rounding.entry(UUID.randomUUID(), at("06:05"))).isEqualTo(at("06:05"));
    }
}
