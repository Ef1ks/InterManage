package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.importguide.dto.ImportCoverage.DateRange;
import com.intermanage.backend.importguide.service.ImportCoverageService;

class ImportCoverageTests {

    private static DateRange range(String from, String to) {
        return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void mergesOverlappingAndTouchingPeriodsAndKeepsGaps() {
        List<DateRange> merged = ImportCoverageService.merge(List.of(
                range("2026-10-01", "2026-11-17"),
                range("2024-01-01", "2024-06-30"),
                range("2024-07-01", "2024-08-15"),
                range("2024-08-01", "2024-08-10"),
                range("2026-11-10", "2026-11-09")));

        assertThat(merged).containsExactly(range("2024-01-01", "2024-08-15"), range("2026-10-01", "2026-11-17"));
    }
}
