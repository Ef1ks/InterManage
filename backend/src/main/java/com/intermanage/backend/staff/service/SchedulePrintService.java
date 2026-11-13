package com.intermanage.backend.staff.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.SchedulePdf;
import com.intermanage.backend.staff.dto.ScheduleResponse;
import com.intermanage.backend.staff.repository.StaffDepartmentRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Wydruk grafiku dla pracowników. Bierze to samo, co widzi drukujący na ekranie ({@link StaffScheduleService#get}),
 * więc kto nie układa grafiku, drukuje ostatnią publikację, a stara wersja drukuje się jako ona. Na kartce zostaje
 * tylko to, co pracownik musi wiedzieć: kiedy pracuje, kiedy ma wolne i gdzie.
 */
@Service
public class SchedulePrintService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Skróty nieobecności na kartce. Kartka wisi na ścianie, więc inne rodzaje niż urlop wypoczynkowy, na żądanie i
     * płatna przerwa (np. turnus rehabilitacyjny, opieka nad dzieckiem) są zawsze „N”, także gdy drukuje osoba z
     * dostępem do Kadr: to dane szczególnej kategorii.
     */
    private static final Map<String, String> ABSENCES = Map.of("ANNUAL", "U", "ON_DEMAND", "UŻ", "B2B_PAID", "UP");
    private static final Map<String, String> SYMBOLS = Map.of("U", "urlop wypoczynkowy", "UŻ", "urlop na żądanie",
            "UP", "płatna przerwa", "N", "nieobecność", "W", "dzień wolny",
            "W5", "wolne z tytułu pięciodniowego tygodnia pracy", "WN", "wolne za pracę w niedzielę lub święto",
            "CW", "czas wolny za nadgodziny");
    private static final List<String> SYMBOL_ORDER = List.of("U", "UŻ", "UP", "N", "W", "W5", "WN", "CW");

    public record File(String fileName, byte[] content) {
    }

    private final StaffScheduleService schedules;
    private final StaffDepartmentRepository departments;
    private final StoreRepository stores;
    private final SchedulePdf pdf;

    public SchedulePrintService(StaffScheduleService schedules, StaffDepartmentRepository departments,
            StoreRepository stores, SchedulePdf pdf) {
        this.schedules = schedules;
        this.departments = departments;
        this.stores = stores;
        this.pdf = pdf;
    }

    @Transactional(readOnly = true)
    public File pdf(UUID storeId, UUID departmentId, String month, Integer version, AppUserPrincipal principal) {
        ScheduleResponse schedule = schedules.get(storeId, departmentId, month, version, principal);
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String departmentName = departments.findById(departmentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu")).getName();
        byte[] content = pdf.pdf(sheet(schedule, departmentName, store));
        return new File("grafik-" + fileName(departmentName) + "-" + schedule.month() + ".pdf", content);
    }

    /** Model kartki z odpowiedzi grafiku (bez bazy, sprawdza go {@code SchedulePdfTests}). */
    public static SchedulePdf.Sheet sheet(ScheduleResponse schedule, String departmentName, Store store) {
        List<SchedulePdf.Day> days = schedule.days().stream()
                .map(day -> new SchedulePdf.Day(day.date(), day.holiday(), day.closed())).toList();
        Map<UUID, String> positionNames = schedule.positions().stream()
                .collect(Collectors.toMap(ScheduleResponse.Position::id, ScheduleResponse.Position::name,
                        (first, second) -> first, LinkedHashMap::new));
        Set<String> symbols = new HashSet<>();
        Map<String, String> dayPositions = new LinkedHashMap<>();
        Set<String> otherStores = new TreeSet<>();
        boolean minutesUsed = false;

        List<SchedulePdf.Section> sections = new ArrayList<>();
        for (Bucket bucket : buckets(schedule)) {
            List<SchedulePdf.Person> people = new ArrayList<>();
            for (ScheduleResponse.Row row : bucket.rows()) {
                if (row.civil() && row.cells().isEmpty()) {
                    continue;
                }
                Map<LocalDate, ScheduleResponse.Cell> byDate = row.cells().stream()
                        .collect(Collectors.toMap(ScheduleResponse.Cell::date, Function.identity(), (a, b) -> a));
                List<SchedulePdf.Cell> cells = new ArrayList<>();
                int minutes = 0;
                for (ScheduleResponse.Day day : schedule.days()) {
                    ScheduleResponse.Cell cell = byDate.get(day.date());
                    SchedulePdf.Cell printed = cell(cell, row, day, positionNames);
                    cells.add(printed);
                    switch (printed.kind()) {
                        case WORK -> {
                            minutes += minutes(cell.start(), cell.end());
                            minutesUsed |= printed.top().contains(":") || printed.bottom().contains(":");
                            if (cell.store() != null) {
                                otherStores.add(cell.store());
                            } else if (printed.note() != null) {
                                dayPositions.put(printed.note(), positionNames.get(cell.positionId()));
                            }
                        }
                        case OFF, ABSENCE -> symbols.add(printed.top());
                        default -> {
                        }
                    }
                }
                // Stanowisko jest już w nagłówku sekcji, więc pod nazwiskiem stoi tylko, skąd jest gość.
                String note = row.guest() ? join("sklep " + row.homeStore(), row.positionName()) : null;
                people.add(new SchedulePdf.Person(row.name(), note, row.civil(), cells, minutes));
            }
            if (!people.isEmpty()) {
                sections.add(new SchedulePdf.Section(bucket.name(), people));
            }
        }

        List<SchedulePdf.LegendItem> legend = new ArrayList<>();
        legend.add(new SchedulePdf.LegendItem(SchedulePdf.Cell.work("6", "14", null),
                "Praca: u góry godzina rozpoczęcia, na dole zakończenia"));
        if (minutesUsed) {
            legend.add(new SchedulePdf.LegendItem(SchedulePdf.Cell.work("13:30", "21:45", null),
                    "Małe cyfry to minuty (13:30–21:45)"));
        }
        for (String symbol : SYMBOL_ORDER) {
            if (symbols.contains(symbol)) {
                SchedulePdf.Kind kind = ABSENCES.containsValue(symbol) || symbol.equals("N") ? SchedulePdf.Kind.ABSENCE
                        : SchedulePdf.Kind.OFF;
                legend.add(new SchedulePdf.LegendItem(new SchedulePdf.Cell(kind, symbol, "", null),
                        capitalize(SYMBOLS.get(symbol))));
            }
        }
        for (String number : otherStores) {
            legend.add(new SchedulePdf.LegendItem(SchedulePdf.Cell.work("6", "14", number),
                    "Praca tego dnia w sklepie nr " + number));
        }
        dayPositions.forEach((symbol, name) -> legend.add(new SchedulePdf.LegendItem(
                SchedulePdf.Cell.work("6", "14", symbol), "Praca tego dnia na stanowisku: " + name)));
        String place = store.getAddress() != null && !store.getAddress().isBlank() ? store.getAddress()
                : store.getName();
        return new SchedulePdf.Sheet(departmentName, place + " · sklep " + store.getNumber(),
                YearMonth.parse(schedule.month()), versionLabel(schedule), draft(schedule),
                LocalDateTime.now(StoreTime.ZONE), days, sections, legend);
    }

    /** Grafik, którego pracownicy jeszcze nie dostali: bieżący stan bez publikacji albo ze zmianami po niej. */
    private static boolean draft(ScheduleResponse schedule) {
        if (schedule.viewedVersion() != null) {
            return false;
        }
        return schedule.versions().isEmpty() || schedule.canEdit() && "CHANGED".equals(schedule.status());
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase() + text.substring(1);
    }

    private record Bucket(String name, List<ScheduleResponse.Row> rows) {
    }

    /** Ten sam podział co tabela na ekranie: własne grupy albo stanowiska, goście na końcu. */
    private static List<Bucket> buckets(ScheduleResponse schedule) {
        List<ScheduleResponse.Row> team = schedule.rows().stream().filter(row -> !row.guest()).toList();
        List<Bucket> buckets = new ArrayList<>();
        Set<ScheduleResponse.Row> placed = new HashSet<>();
        if (!schedule.groups().isEmpty()) {
            Set<UUID> groupIds = schedule.groups().stream().map(ScheduleResponse.Group::id).collect(Collectors.toSet());
            buckets.add(new Bucket("Bez grupy", team.stream()
                    .filter(row -> row.groupId() == null || !groupIds.contains(row.groupId())).toList()));
            schedule.groups().forEach(group -> buckets.add(new Bucket(group.name(),
                    team.stream().filter(row -> group.id().equals(row.groupId())).toList())));
        } else {
            schedule.positions().forEach(position -> buckets.add(new Bucket(position.name(),
                    team.stream().filter(row -> position.id().equals(row.positionId())).toList())));
        }
        buckets.forEach(bucket -> placed.addAll(bucket.rows()));
        List<ScheduleResponse.Row> rest = team.stream().filter(row -> !placed.contains(row)).toList();
        if (!rest.isEmpty()) {
            buckets.add(new Bucket("Pozostali", rest));
        }
        List<ScheduleResponse.Row> guests = schedule.rows().stream().filter(ScheduleResponse.Row::guest).toList();
        if (!guests.isEmpty()) {
            buckets.add(new Bucket("Z innych sklepów i działów", guests));
        }
        List<Bucket> filled = buckets.stream().filter(bucket -> !bucket.rows().isEmpty()).toList();
        // Jedna grupa „Bez grupy” to po prostu lista, bez nagłówka.
        if (filled.size() == 1 && filled.getFirst().name().equals("Bez grupy")) {
            return List.of(new Bucket("", filled.getFirst().rows()));
        }
        return filled;
    }

    private static SchedulePdf.Cell cell(ScheduleResponse.Cell cell, ScheduleResponse.Row row,
            ScheduleResponse.Day day, Map<UUID, String> positionNames) {
        if (cell == null) {
            return day.closed() ? new SchedulePdf.Cell(SchedulePdf.Kind.CLOSED, "", "", null) : SchedulePdf.Cell.EMPTY;
        }
        if (cell.absent()) {
            // Godziny pod urlopem to rozkład dnia urlopu, nie praca.
            return new SchedulePdf.Cell(SchedulePdf.Kind.ABSENCE, ABSENCES.getOrDefault(cell.absence(), "N"), "",
                    null);
        }
        if (cell.start() != null) {
            String note = cell.store() != null ? cell.store()
                    : cell.positionId() != null && !cell.positionId().equals(row.positionId())
                            && positionNames.containsKey(cell.positionId())
                                    ? positionShort(positionNames.get(cell.positionId()))
                                    : null;
            return new SchedulePdf.Cell(SchedulePdf.Kind.WORK, hour(cell.start()), hour(cell.end()), note);
        }
        if (cell.dayOff() != null) {
            return new SchedulePdf.Cell(SchedulePdf.Kind.OFF, cell.dayOff().name(), "", null);
        }
        if (cell.timeOffMinutes() != null) {
            return new SchedulePdf.Cell(SchedulePdf.Kind.OFF, "CW", "", null);
        }
        return day.closed() ? new SchedulePdf.Cell(SchedulePdf.Kind.CLOSED, "", "", null) : SchedulePdf.Cell.EMPTY;
    }

    /** Wersja na kartce, żeby pracownik wiedział, czy ma aktualny grafik. */
    private static String versionLabel(ScheduleResponse schedule) {
        List<ScheduleResponse.Version> versions = schedule.versions();
        if (schedule.viewedVersion() != null) {
            return versions.stream().filter(version -> version.number() == schedule.viewedVersion()).findFirst()
                    .map(SchedulePrintService::published).orElse("Wersja " + schedule.viewedVersion());
        }
        if (versions.isEmpty()) {
            return schedule.canEdit() ? "Wersja robocza, jeszcze nieopublikowana" : "Grafik jeszcze nieopublikowany";
        }
        ScheduleResponse.Version last = versions.getLast();
        if (schedule.canEdit() && "CHANGED".equals(schedule.status())) {
            return "Wersja robocza: zmiany po wersji " + last.number() + ", jeszcze nieopublikowane";
        }
        return published(last);
    }

    private static String published(ScheduleResponse.Version version) {
        return "Wersja " + version.number() + " z " + DATE.format(version.publishedAt().atZone(StoreTime.ZONE));
    }

    /** 06:00 → 6, 06:30 → 6:30: pełne godziny bez zer czyta się szybciej. */
    static String hour(String time) {
        LocalTime value = LocalTime.parse(time, TIME);
        return value.getMinute() == 0 ? String.valueOf(value.getHour())
                : value.getHour() + ":" + String.format("%02d", value.getMinute());
    }

    private static int minutes(String start, String end) {
        int from = LocalTime.parse(start, TIME).toSecondOfDay() / 60;
        int to = LocalTime.parse(end, TIME).toSecondOfDay() / 60;
        return to > from ? to - from : to + 24 * 60 - from;
    }

    /** Jak skrót stanowiska w komórce na ekranie: pierwsze litery słów albo dwie litery jednego słowa. */
    static String positionShort(String name) {
        String[] words = name.trim().split("\\s+");
        String text = words.length > 1
                ? java.util.Arrays.stream(words).map(word -> word.substring(0, 1)).collect(Collectors.joining())
                : words[0].substring(0, Math.min(2, words[0].length()));
        return text.substring(0, Math.min(3, text.length())).toUpperCase();
    }

    private static String join(String... parts) {
        return java.util.Arrays.stream(parts).filter(Objects::nonNull).filter(part -> !part.isBlank())
                .collect(Collectors.joining(" · "));
    }

    private static String fileName(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ł', 'l')
                .replace('Ł', 'L');
        return plain.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
    }
}
