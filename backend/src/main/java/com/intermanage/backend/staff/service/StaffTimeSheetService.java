package com.intermanage.backend.staff.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.ScheduleCalendar;
import com.intermanage.backend.staff.component.TimeCard;
import com.intermanage.backend.staff.component.TimeSheetPdf;
import com.intermanage.backend.staff.dto.ScheduleDepartment;
import com.intermanage.backend.staff.dto.TimeCardResponse;
import com.intermanage.backend.staff.dto.TimeSettingsResponse;
import com.intermanage.backend.staff.entity.ScheduleDayOffKind;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffScheduleDayOff;
import com.intermanage.backend.staff.repository.StaffDisabilityRepository;
import com.intermanage.backend.staff.repository.StaffScheduleDayOffRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Miesięczna ewidencja czasu pracy z kart: wydruk karty ewidencji (PDF, kartka na pracownika), plik importu do
 * Symfonii R2Płatnik i zestawienie godzin sklepu do Excela. Pracownika w Symfonii wskazuje numer karty RCP nadany w
 * aplikacji ({@code staff_employees.rcp_number}). Liczby bierze z tego samego widoku co ekran Czasu pracy
 * ({@link StaffTimeService#view}), więc karta zgadza się z tym, co widzi kierownik. Liczby mają tylko dni
 * zakończone ({@link #finished}).
 */
@Service
public class StaffTimeSheetService {

    private static final DateTimeFormatter FOR_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter RCP_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter RCP_TIME = DateTimeFormatter.ofPattern("H:mm");
    private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");
    private static final Map<String, String> ABSENCE_SYMBOLS = Map.of("ANNUAL", "U", "ON_DEMAND", "UŻ", "CHILD_CARE",
            "OD", "FORCE_MAJEURE", "SW", "CARE", "UO", "REHABILITATION", "TR", "OCCASIONAL", "OK", "UNPAID", "UB",
            "OTHER", "N");

    private final StaffTimeService time;
    private final StaffScheduleDayOffRepository daysOff;
    private final StaffDisabilityRepository disabilities;
    private final StoreRepository stores;
    private final TimeSheetPdf pdf;
    private final XlsxWriter xlsx;

    public StaffTimeSheetService(StaffTimeService time, StaffScheduleDayOffRepository daysOff,
            StaffDisabilityRepository disabilities, StoreRepository stores, TimeSheetPdf pdf, XlsxWriter xlsx) {
        this.time = time;
        this.daysOff = daysOff;
        this.disabilities = disabilities;
        this.stores = stores;
        this.pdf = pdf;
        this.xlsx = xlsx;
    }

    public record File(String fileName, byte[] content) {
    }

    /** Karta ewidencji działu za miesiąc; z {@code employeeId} tylko tej osoby. */
    @Transactional(readOnly = true)
    public File pdf(UUID storeId, UUID departmentId, YearMonth month, UUID employeeId, AppUserPrincipal principal) {
        Store store = store(storeId);
        ScheduleDepartment department = time.departments(storeId, principal).stream()
                .filter(item -> item.id().equals(departmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu"));
        List<Person> people = people(storeId, department, month, principal).stream()
                .filter(person -> employeeId == null || person.employeeId().equals(employeeId))
                .toList();
        if (employeeId != null && people.isEmpty()) {
            throw ApiException.notFound("Ten pracownik nie ma karty czasu pracy w tym miesiącu");
        }
        TimeSettingsResponse.Employer employer = time.employerOf(storeId);
        byte[] content = pdf.pdf(new TimeSheetPdf.Sheet(
                new TimeSheetPdf.Employer(employer.name(), employer.address(), employer.nip()),
                "sklep " + store.getNumber(), month, LocalDateTime.now(StoreTime.ZONE),
                people.stream().map(Person::sheet).toList()));
        String who = people.size() == 1 ? fileName(people.getFirst().sheet().name()) : fileName(department.name());
        return new File("karta-czasu-pracy-" + month + "-" + who + ".pdf", content);
    }

    /**
     * Zestawienie miesiąca dla całego sklepu: wiersz na pracownika z umową o pracę, z numerem karty RCP, godziny jako
     * liczby dziesiętne (1:30 = 1,5).
     */
    @Transactional(readOnly = true)
    public File xlsx(UUID storeId, YearMonth month, AppUserPrincipal principal) {
        Store store = store(storeId);
        List<Person> people = storePeople(storeId, month, principal);
        people.sort(Comparator.comparing((Person person) -> surname(person.sheet().name()).toLowerCase())
                .thenComparing(person -> person.sheet().name().toLowerCase()));

        List<XlsxWriter.Column> columns = List.of(new XlsxWriter.Column("Nr karty RCP", 10),
                new XlsxWriter.Column("Nazwisko", 18), new XlsxWriter.Column("Imię", 14),
                new XlsxWriter.Column("Dział", 16), new XlsxWriter.Column("Stanowisko", 18),
                new XlsxWriter.Column("Rok", 6), new XlsxWriter.Column("Miesiąc", 8),
                new XlsxWriter.Column("Godziny przepracowane", 12), new XlsxWriter.Column("Nadgodziny dobowe", 12),
                new XlsxWriter.Column("Godziny w porze nocnej", 12),
                new XlsxWriter.Column("Godziny w niedziele i święta", 14),
                new XlsxWriter.Column("Przyjęte poza grafikiem", 12), new XlsxWriter.Column("Godziny w grafiku", 12),
                new XlsxWriter.Column("Braki nieusprawiedliwione", 14), new XlsxWriter.Column("Czeka na decyzję", 12),
                new XlsxWriter.Column("Dni pracy", 8), new XlsxWriter.Column("Urlop wypoczynkowy (dni)", 12),
                new XlsxWriter.Column("w tym na żądanie (dni)", 12), new XlsxWriter.Column("Inne nieobecności", 22),
                new XlsxWriter.Column("W5 (dni)", 8), new XlsxWriter.Column("WN (dni)", 8),
                new XlsxWriter.Column("Uwagi", 30));
        List<XlsxWriter.Row> rows = new ArrayList<>();
        for (Person person : people) {
            TimeSheetPdf.Totals totals = person.sheet().totals();
            Map<String, Integer> marks = totals.markDays();
            int annual = marks.getOrDefault("U", 0) + marks.getOrDefault("UŻ", 0);
            String others = marks.entrySet().stream()
                    .filter(entry -> !List.of("U", "UŻ", "W", "W5", "WN").contains(entry.getKey()))
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining(", "));
            List<String> notes = new ArrayList<>();
            if (totals.pending() > 0) {
                notes.add("czas poza grafikiem bez decyzji");
            }
            if (person.missingExits() > 0) {
                notes.add("brak wyjścia: " + person.missingExits() + " dni");
            }
            String name = person.sheet().name();
            rows.add(XlsxWriter.Row.of(BigDecimal.valueOf(person.rcpNumber()), surname(name), firstNames(name),
                    person.sheet().departmentName(), person.sheet().positionName(),
                    BigDecimal.valueOf(month.getYear()), BigDecimal.valueOf(month.getMonthValue()),
                    hours(totals.worked()), hours(totals.dailyOvertime()), hours(totals.night()),
                    hours(totals.sundayHoliday()), hours(totals.outsideAccepted()), hours(person.planned()),
                    hours(totals.shortfall()), hours(totals.pending()), BigDecimal.valueOf(totals.workDays()),
                    BigDecimal.valueOf(annual), BigDecimal.valueOf(marks.getOrDefault("UŻ", 0)),
                    others.isEmpty() ? null : others, BigDecimal.valueOf(marks.getOrDefault("W5", 0)),
                    BigDecimal.valueOf(marks.getOrDefault("WN", 0)), notes.isEmpty() ? null : String.join("; ", notes)));
        }
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        List<XlsxWriter.Row> intro = new ArrayList<>();
        intro.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold("Czas pracy " + month + ", sklep " + store.getNumber())));
        if (!today.isAfter(month.atEndOfMonth())) {
            intro.add(XlsxWriter.Row.of("Miesiąc jeszcze trwa: godziny za zakończone dni, stan na "
                    + FOR_DATE.format(today)));
        }
        intro.add(XlsxWriter.Row.of("Godziny w liczbach dziesiętnych (1,5 = 1 h 30 min). Pora nocna 22-6."));
        return new File("czas-pracy-" + store.getNumber() + "-" + month + ".xlsx",
                xlsx.writeRows("Czas pracy " + month, intro, columns, rows));
    }

    /**
     * Plik dla Symfonii R2Płatnik („Zbiory - Import danych - Import RCP - Import ewidencji czasu pracy”): linia na
     * odcinek czasu zaliczonego do pracy, {@code karta;dd-MM-rrrr;wejście;wyjście;zal. wejście;zal. wyjście;}, pola
     * po średniku, CRLF. Godziny zaliczone są takie same jak wejście i wyjście, więc R2Płatnik bierze czas z pliku
     * zamiast z harmonogramu (przerwy niepłatne, decyzje o nadgodzinach i tolerancja są już policzone). Numer karty
     * RCP trzeba raz wpisać w R2Płatniku w Aktualnym zatrudnieniu. Urlopy i nieobecności wprowadza się tam osobno.
     */
    @Transactional(readOnly = true)
    public File rcp(UUID storeId, YearMonth month, AppUserPrincipal principal) {
        Store store = store(storeId);
        StringBuilder content = new StringBuilder();
        for (Person person : storePeople(storeId, month, principal)) {
            for (TimeCard.Interval interval : person.counted()) {
                String start = RCP_TIME.format(interval.start());
                String end = RCP_TIME.format(interval.end());
                content.append(person.rcpNumber()).append(';').append(RCP_DATE.format(interval.start())).append(';')
                        .append(start).append(';').append(end).append(';').append(start).append(';').append(end)
                        .append(";\r\n");
            }
        }
        return new File("rcp-" + store.getNumber() + "-" + month + ".txt", content.toString().getBytes(WINDOWS_1250));
    }

    private List<Person> storePeople(UUID storeId, YearMonth month, AppUserPrincipal principal) {
        List<Person> people = new ArrayList<>();
        for (ScheduleDepartment department : time.departments(storeId, principal)) {
            people.addAll(people(storeId, department, month, principal));
        }
        return people;
    }

    // ---------------------------------------------------------------- obliczenia

    /**
     * Odcinki dnia liczone do czasu pracy (praca, przyjęte nadgodziny, braki usprawiedliwione i w tolerancji), od
     * najwcześniejszego, a stykające się połączone w jeden (przerwa niepłatna je rozdziela).
     */
    static List<TimeCard.Interval> countedIntervals(TimeCardResponse.DayCard card) {
        List<TimeCard.Interval> result = new ArrayList<>();
        card.segments().stream()
                .filter(segment -> TimeCard.Status.valueOf(segment.status()).counted())
                .sorted(Comparator.comparing(TimeCardResponse.Segment::start))
                .forEach(segment -> {
                    TimeCard.Interval last = result.isEmpty() ? null : result.getLast();
                    if (last != null && !segment.start().isAfter(last.end())) {
                        if (segment.end().isAfter(last.end())) {
                            result.set(result.size() - 1, new TimeCard.Interval(last.start(), segment.end()));
                        }
                    } else {
                        result.add(new TimeCard.Interval(segment.start(), segment.end()));
                    }
                });
        return result;
    }

    /** {@code counted}: odcinki czasu zaliczonego do pracy (dni zakończone), sąsiednie połączone. */
    private record Person(UUID employeeId, Integer rcpNumber, TimeSheetPdf.Person sheet, int planned,
            int missingExits, List<TimeCard.Interval> counted) {
    }

    private List<Person> people(UUID storeId, ScheduleDepartment department, YearMonth month,
            AppUserPrincipal principal) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        TimeCardResponse cards = time.view(storeId, department.id(), from, to, principal);
        // Karta ewidencji i pliki do kadr dotyczą umowy o pracę; ewidencja godzin zlecenia jest tylko na ekranie.
        List<TimeCardResponse.Row> employment = cards.rows().stream().filter(row -> !row.civil()).toList();
        if (employment.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = employment.stream().map(TimeCardResponse.Row::employeeId).toList();
        Map<UUID, Map<LocalDate, StaffScheduleDayOff>> offOf = new HashMap<>();
        daysOff.findOfEmployees(ids, from, to).forEach(off -> offOf
                .computeIfAbsent(off.getEmployee().getId(), key -> new HashMap<>()).putIfAbsent(off.getDay(), off));
        Map<UUID, List<StaffDisability>> disabilityOf = disabilities.findActive(ids).stream()
                .collect(Collectors.groupingBy(disability -> disability.getEmployee().getId()));
        Map<LocalDate, String> holidays = cards.days().stream().filter(day -> day.holiday() != null)
                .collect(Collectors.toMap(TimeCardResponse.Day::date, TimeCardResponse.Day::holiday));
        LocalDateTime now = LocalDateTime.now(StoreTime.ZONE);

        List<Person> result = new ArrayList<>();
        for (TimeCardResponse.Row row : employment) {
            Map<LocalDate, StaffScheduleDayOff> off = offOf.getOrDefault(row.employeeId(), Map.of());
            List<StaffDisability> disability = disabilityOf.getOrDefault(row.employeeId(), List.of());
            List<TimeSheetPdf.Day> days = new ArrayList<>();
            Map<String, Integer> marks = new LinkedHashMap<>();
            int worked = 0;
            int dailyOvertime = 0;
            int night = 0;
            int sundayHoliday = 0;
            int workDays = 0;
            int shortfall = 0;
            int pending = 0;
            int outside = 0;
            int planned = 0;
            int missingExits = 0;
            List<TimeCard.Interval> countedIntervals = new ArrayList<>();
            for (TimeCardResponse.DayCard card : row.days()) {
                LocalDate date = card.date();
                boolean counted = finished(card, now);
                String mark = mark(card, off.get(date));
                if (mark != null) {
                    marks.merge(mark, 1, Integer::sum);
                }
                int dayNight = 0;
                int daySunday = 0;
                for (TimeCardResponse.Segment segment : card.segments()) {
                    if (!TimeCard.Status.valueOf(segment.status()).counted()) {
                        continue;
                    }
                    dayNight += night(segment.start(), segment.end());
                    daySunday += sundayOrHoliday(segment.start(), segment.end());
                }
                int limit = dailyLimit(disability, date);
                int dayOvertime = Math.max(0, card.worked() - limit);
                days.add(new TimeSheetPdf.Day(date, holidays.get(date), card.plannedStart(), card.plannedEnd(),
                        firstIn(card), lastOut(card), counted, card.worked(), dayOvertime, dayNight, daySunday, mark,
                        note(card, off.get(date), holidays.get(date), counted)));
                if (!counted) {
                    continue;
                }
                countedIntervals.addAll(countedIntervals(card));
                worked += card.worked();
                dailyOvertime += dayOvertime;
                night += dayNight;
                sundayHoliday += daySunday;
                workDays += card.worked() > 0 ? 1 : 0;
                shortfall += card.shortfall();
                pending += card.pending();
                outside += card.overtime();
                planned += card.absence() == null ? card.planned() : 0;
                missingExits += card.missingExit() ? 1 : 0;
            }
            TimeSheetPdf.Totals totals = new TimeSheetPdf.Totals(worked, dailyOvertime, night, sundayHoliday, workDays,
                    marks, shortfall, pending, outside);
            result.add(new Person(row.employeeId(), row.rcpNumber(), new TimeSheetPdf.Person(row.name(),
                    row.rcpNumber(), row.positionName(), department.name(), days, totals), planned, missingExits,
                    countedIntervals));
        }
        return result;
    }

    /**
     * Dzień ma liczby, gdy się skończył: wcześniejszy niż dziś, a dziś dopiero po zmianie z grafiku i bez wejścia
     * bez wyjścia (inaczej brak do końca zmiany wyglądałby jak nieusprawiedliwiony).
     */
    public static boolean finished(TimeCardResponse.DayCard card, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (card.date().isBefore(today)) {
            return true;
        }
        return card.date().equals(today) && card.openSince() == null
                && (card.plannedEnd() == null || !card.plannedEnd().isAfter(now));
    }

    /**
     * Urlop i nieobecność tylko w dniu z grafikiem (dzień pracy, art. 154²), inaczej dzień wolny z grafiku (W, W5, WN).
     */
    private static String mark(TimeCardResponse.DayCard card, StaffScheduleDayOff off) {
        if (card.absence() != null && card.plannedStart() != null) {
            return ABSENCE_SYMBOLS.getOrDefault(card.absence(), "N");
        }
        return off == null ? null : off.getKind().name();
    }

    private static String note(TimeCardResponse.DayCard card, StaffScheduleDayOff off, String holiday,
            boolean counted) {
        List<String> parts = new ArrayList<>();
        if (holiday != null) {
            parts.add(holiday);
        }
        if (off != null && off.getKind() == ScheduleDayOffKind.WN && off.getForDate() != null) {
            parts.add("WN za " + FOR_DATE.format(off.getForDate()));
        }
        if (counted) {
            if (card.missingExit()) {
                parts.add("brak wyjścia");
            }
            if (card.overtime() > 0) {
                parts.add("poza gr. " + hm(card.overtime()));
            }
            if (card.shortfall() > 0) {
                parts.add("brak " + hm(card.shortfall()));
            }
            if (card.pending() > 0) {
                parts.add("bez decyzji " + hm(card.pending()));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static LocalDateTime firstIn(TimeCardResponse.DayCard card) {
        return card.punches().stream().map(TimeCardResponse.Punch::at).min(Comparator.naturalOrder()).orElse(null);
    }

    /** Ostatnie odbicie, o ile zamyka parę (przy wejściu bez wyjścia nie ma czego wpisać). */
    private static LocalDateTime lastOut(TimeCardResponse.DayCard card) {
        if (card.openSince() != null || card.punches().size() < 2) {
            return null;
        }
        return card.punches().stream().map(TimeCardResponse.Punch::at).max(Comparator.naturalOrder()).orElse(null);
    }

    /** Minuty odcinka w porze nocnej 22-6 (jak w ostrzeżeniach Kadr; właściwa pora jest w regulaminie pracy). */
    static int night(LocalDateTime start, LocalDateTime end) {
        int minutes = 0;
        for (LocalDate day = start.toLocalDate().minusDays(1); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            minutes += overlap(start, end, day.atTime(22, 0), day.plusDays(1).atTime(6, 0));
        }
        return minutes;
    }

    /** Minuty odcinka przypadające w niedzielę albo święto (doba kalendarzowa). */
    static int sundayOrHoliday(LocalDateTime start, LocalDateTime end) {
        int minutes = 0;
        for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            if (ScheduleCalendar.isSunday(day) || ScheduleCalendar.holiday(day) != null) {
                minutes += overlap(start, end, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
            }
        }
        return minutes;
    }

    private static int overlap(LocalDateTime start, LocalDateTime end, LocalDateTime from, LocalDateTime to) {
        LocalDateTime a = start.isAfter(from) ? start : from;
        LocalDateTime b = end.isBefore(to) ? end : to;
        return b.isAfter(a) ? (int) java.time.Duration.between(a, b).toMinutes() : 0;
    }

    /** Jak w Grafiku: 7 h przy znacznym stopniu bez zgody lekarza (art. 15 ust. 2 ustawy o rehabilitacji). */
    private static int dailyLimit(List<StaffDisability> disabilities, LocalDate day) {
        boolean seven = disabilities.stream().filter(disability -> disability.activeOn(day))
                .anyMatch(disability -> disability.isSignificant() && !disability.isDoctorConsent());
        return seven ? 7 * 60 : 8 * 60;
    }

    private static BigDecimal hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private static String hm(int minutes) {
        return minutes / 60 + ":" + String.format("%02d", minutes % 60);
    }

    /** Nazwisko to ostatnie słowo (Anna Maria Nowak-Kowalska → Nowak-Kowalska), imiona to reszta. */
    static String surname(String name) {
        String[] words = name.strip().split("\\s+");
        return words[words.length - 1];
    }

    static String firstNames(String name) {
        String[] words = name.strip().split("\\s+");
        return words.length < 2 ? "" : String.join(" ", java.util.Arrays.copyOf(words, words.length - 1));
    }

    private static String fileName(String text) {
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ł', 'l').replace('Ł', 'L');
        return plain.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }
}
