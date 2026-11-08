package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.staff.component.TimeSheetPdf;
import com.lowagie.text.pdf.PdfReader;

/** Karta ewidencji czasu pracy: 31 dni z uwagami przy każdym mieści się na jednej kartce na pracownika. */
class TimeSheetPdfTests {

    private final TimeSheetPdf pdf = new TimeSheetPdf(new PdfFonts());

    @Test
    void fullMonthFitsOnePagePerEmployee() throws IOException {
        YearMonth month = YearMonth.of(2026, 10);
        List<TimeSheetPdf.Day> days = month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                .map(TimeSheetPdfTests::day).toList();
        Map<String, Integer> marks = new LinkedHashMap<>();
        marks.put("U", 3);
        marks.put("W5", 4);
        marks.put("WN", 2);
        TimeSheetPdf.Totals totals = new TimeSheetPdf.Totals(9600, 120, 600, 960, 20, marks, 30, 15, 60);
        TimeSheetPdf.Person person = new TimeSheetPdf.Person("Anna Maria Nowak-Kowalska", 1001, "Kasjer", "Kasy", days,
                totals);
        byte[] content = pdf.pdf(new TimeSheetPdf.Sheet(
                new TimeSheetPdf.Employer("TRYTOMA Sp. z o.o.", "ul. Polna 1, 65-001 Zielona Góra", "9291234567"),
                "sklep 10023", month, LocalDateTime.of(2026, 11, 8, 12, 0), List.of(person, person)));
        String out = System.getProperty("timesheet.out");
        if (out != null) {
            Files.write(Path.of(out), content);
        }
        try (PdfReader reader = new PdfReader(content)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(2);
        }
    }

    private static TimeSheetPdf.Day day(LocalDate date) {
        LocalDateTime start = date.atTime(22, 0);
        return new TimeSheetPdf.Day(date, date.getDayOfMonth() == 11 ? "Narodowe Święto Niepodległości" : null,
                start, start.plusHours(8), start.minusMinutes(5), start.plusHours(8).plusMinutes(10), true, 485, 5,
                480, 120, date.getDayOfMonth() % 7 == 0 ? "W5" : null,
                "poza grafikiem 0:05, brak 0:10, bez decyzji 0:10, brak wyjścia");
    }
}
