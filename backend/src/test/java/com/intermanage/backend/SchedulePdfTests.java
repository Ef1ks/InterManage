package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.staff.component.SchedulePdf;
import com.intermanage.backend.staff.dto.ScheduleResponse;
import com.intermanage.backend.staff.entity.ScheduleDayOffKind;
import com.intermanage.backend.staff.service.SchedulePrintService;
import com.intermanage.backend.store.entity.Store;
import com.lowagie.text.pdf.PdfReader;

/** Wydruk grafiku: pełny dział na jednej kartce, a na ścianę nie trafiają dane szczególnej kategorii. */
class SchedulePdfTests {

    private static final YearMonth MONTH = YearMonth.of(2026, 12);
    private static final UUID CASHIER = UUID.randomUUID();
    private static final UUID FLOOR = UUID.randomUUID();

    private final SchedulePdf pdf = new SchedulePdf(new PdfFonts());

    @Test
    void wholeDepartmentFitsOnePage() throws IOException {
        List<ScheduleResponse.Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row("Anna Maria Nowak-Kowalska " + i, i % 3 == 0 ? FLOOR : CASHIER, false, cells(i)));
        }
        byte[] content = pdf.pdf(SchedulePrintService.sheet(schedule(rows), "Kasy i obsługa sali", store()));
        String out = System.getProperty("schedule.out");
        if (out != null) {
            Files.write(Path.of(out), content);
        }
        try (PdfReader reader = new PdfReader(content)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void kitchenFitsOnePage() throws IOException {
        List<ScheduleResponse.Row> rows = new ArrayList<>();
        String[] names = { "Bartek", "Ewa Nowak", "Kasia", "Marek Wiśniewski", "Ola", "Piotr", "Zosia Kowalczyk",
                "Tomek", "Agnieszka", "Michał", "Grażyna", "Staszek", "Ania" };
        for (int i = 0; i < names.length; i++) {
            rows.add(row(names[i], i % 3 == 0 ? FLOOR : CASHIER, false, cells(i)));
        }
        byte[] content = pdf.pdf(SchedulePrintService.sheet(schedule(rows), "Kuchnia", store()));
        String out = System.getProperty("schedule.out");
        if (out != null) {
            Files.write(Path.of(out.replace(".pdf", "-kuchnia.pdf")), content);
        }
        try (PdfReader reader = new PdfReader(content)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void printsOnlyAnnualLeaveByName() {
        LocalDate day = MONTH.atDay(2);
        ScheduleResponse.Row row = row("Jan Kowalski", CASHIER, false, List.of(
                cell(day, null, null, true, "REHABILITATION", null),
                cell(day.plusDays(1), "06:00", "14:00", true, "ANNUAL", null),
                cell(day.plusDays(2), "06:30", "14:30", false, null, null),
                cell(day.plusDays(3), null, null, false, null, ScheduleDayOffKind.W5)));
        SchedulePdf.Sheet sheet = SchedulePrintService.sheet(schedule(List.of(row)), "Kasy", store());

        SchedulePdf.Person person = sheet.sections().getFirst().people().getFirst();
        List<SchedulePdf.Cell> cells = person.cells();
        assertThat(cells.get(1).top()).isEqualTo("N");
        assertThat(cells.get(2).top()).isEqualTo("U");
        assertThat(cells.get(3)).extracting(SchedulePdf.Cell::top, SchedulePdf.Cell::bottom)
                .containsExactly("6:30", "14:30");
        assertThat(cells.get(4).top()).isEqualTo("W5");
        // Godziny pod urlopem to rozkład dnia urlopu, nie praca.
        assertThat(person.minutes()).isEqualTo(480);
        assertThat(sheet.legend()).extracting(item -> item.sample().top() + " " + item.text())
                .contains("N Nieobecność").noneMatch(text -> text.contains("turnus"));
    }

    private static List<ScheduleResponse.Cell> cells(int seed) {
        List<ScheduleResponse.Cell> cells = new ArrayList<>();
        for (LocalDate day : MONTH.atDay(1).datesUntil(MONTH.atEndOfMonth().plusDays(1)).toList()) {
            int n = day.getDayOfMonth() + seed;
            if (n % 7 == 0) {
                cells.add(cell(day, null, null, false, null, ScheduleDayOffKind.W5));
            } else if (seed % 10 == 0 && day.getDayOfMonth() % 9 == 4) {
                cells.add(new ScheduleResponse.Cell(day, "07:00", "15:00", UUID.randomUUID(), "10024", false, null,
                        null, null, null, null, null, null));
            } else if (n % 9 == 0) {
                cells.add(cell(day, null, null, false, null, ScheduleDayOffKind.W));
            } else if (n % 11 == 0) {
                cells.add(cell(day, null, null, true, "ANNUAL", null));
            } else if (n % 2 == 0) {
                cells.add(cell(day, "06:00", "14:00", false, null, null));
            } else {
                cells.add(cell(day, "13:30", "21:45", false, null, null));
            }
        }
        return cells;
    }

    private static ScheduleResponse.Cell cell(LocalDate date, String start, String end, boolean absent,
            String absence, ScheduleDayOffKind dayOff) {
        return new ScheduleResponse.Cell(date, start, end, null, null, absent, absence, dayOff, null, null, null, null,
                null);
    }

    private static ScheduleResponse.Row row(String name, UUID position, boolean civil,
            List<ScheduleResponse.Cell> cells) {
        return new ScheduleResponse.Row(UUID.randomUUID(), name, position, position == CASHIER ? "Kasjer" : "Sala",
                null, "10023", false, null, null, null, null, null, List.of(), cells, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, civil, null, null, null);
    }

    private static ScheduleResponse schedule(List<ScheduleResponse.Row> rows) {
        List<ScheduleResponse.Day> days = MONTH.atDay(1).datesUntil(MONTH.atEndOfMonth().plusDays(1))
                .map(day -> new ScheduleResponse.Day(day,
                        day.getDayOfMonth() == 25 ? "Boże Narodzenie" : day.getDayOfMonth() == 24 ? "Wigilia" : null,
                        day.getDayOfMonth() == 25))
                .toList();
        return new ScheduleResponse(UUID.randomUUID(), MONTH.toString(), "PUBLISHED", false, false, null,
                MONTH.atDay(1), MONTH.atEndOfMonth(),
                rows.size() == 13 ? List.of()
                        : List.of(new ScheduleResponse.Version(1, Instant.parse("2026-11-20T10:00:00Z"), "Kierownik", false)),
                days,
                List.of(new ScheduleResponse.Position(CASHIER, "Kasjer", null, null, List.of(), List.of()),
                        new ScheduleResponse.Position(FLOOR, "Sala", null, null, List.of(), List.of())),
                List.of(), rows, List.of(), false, Map.of());
    }

    private static Store store() {
        return new Store("10023", "Kisielińska", "Szosa Kisielińska 22");
    }
}
