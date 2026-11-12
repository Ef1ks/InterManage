package com.intermanage.backend.staff.component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.intermanage.backend.staff.component.ScheduleCalendar.Period;

/**
 * Ostrzeżenia grafiku jednego pracownika, sprawdzone na tekście KP (Dz.U. 2026 poz. 1245) i ustawy o ograniczeniu handlu
 * (Dz.U. 2025 poz. 301). Niczego nie blokują. System podstawowy, bez art. 135-138. Bez bazy, testy: {@code ScheduleLawTests}.
 * Limit dobowy podajemy z zewnątrz, a treść ostrzeżenia nie mówi, skąd krótszy limit: grafik układa kierownik, który nie
 * musi mieć dostępu do Kadr, a powodem bywa orzeczenie (dane szczególnej kategorii).
 */
public final class ScheduleRules {

    private static final long DAILY_REST_MINUTES = 11 * 60;
    private static final long WEEKLY_REST_MINUTES = 35 * 60;
    private static final long SHORT_WEEKLY_REST_MINUTES = 24 * 60;
    private static final int SUNDAYS_IN_ROW = 4;
    public static final int SUNDAY_DAY_OFF_DAYS = 6;
    private static final int MAX_SUNDAYS_BEFORE_CHRISTMAS = 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    private ScheduleRules() {
    }

    /** Koniec przed początkiem to zmiana przez północ. */
    public record Shift(LocalDate day, LocalTime start, LocalTime end) {

        public LocalDateTime startsAt() {
            return day.atTime(start);
        }

        public LocalDateTime endsAt() {
            return (end.isAfter(start) ? day : day.plusDays(1)).atTime(end);
        }

        public int minutes() {
            return (int) Duration.between(startsAt(), endsAt()).toMinutes();
        }
    }

    /** Oznaczone WN: dzień wolny {@code day} za pracę w {@code forDate}. */
    public record DayOff(LocalDate day, LocalDate forDate) {
    }

    /**
     * To, czego reguły nie wyczytają ze zmian: święta czynne w sklepie pracownika, oznaczone WN i ruchomy czas pracy
     * sklepu ({@code flexibleTime}, art. 140¹ KP: ponowna praca w tej samej dobie nie jest nadgodzinami).
     */
    public record Days(Predicate<LocalDate> openHoliday, List<DayOff> compensating, boolean flexibleTime) {

        public static final Days NONE = new Days(day -> false, List.of(), false);
    }

    /** {@code day} pusty: dotyczy całego okresu. */
    public record Warning(LocalDate day, String message, String basis) {
    }

    /**
     * @param shifts zmiany z okresu i 4 tygodni przed nim, bo odpoczynek i niedziele nie kończą się na granicy okresu
     * @param dailyLimit limit minut na dobę danego dnia
     * @param periodNorm wymiar okresu w minutach, pusty bez umowy o pracę
     * @param periodWorked minuty okresu bez dni nieobecności: przepracowane z kart za dni, które minęły, zaplanowane za
     *        pozostałe ({@link PeriodBalance})
     */
    public static List<Warning> check(Period period, List<Shift> shifts, Function<LocalDate, Integer> dailyLimit,
            Integer periodNorm, int periodWorked, Days days) {
        List<Shift> sorted = shifts.stream().sorted(Comparator.comparing(Shift::day)).toList();
        List<Warning> result = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            Shift shift = sorted.get(index);
            if (!period.contains(shift.day())) {
                continue;
            }
            closedDay(shift, days, result);
            if (shift.minutes() > dailyLimit.apply(shift.day())) {
                result.add(new Warning(shift.day(), "Zmiana dłuższa niż " + hours(dailyLimit.apply(shift.day())) + " h",
                        "art. 129 § 1 KP"));
            }
            if (index > 0) {
                betweenShifts(sorted.get(index - 1), shift, dailyLimit, days.flexibleTime(), result);
            }
        }
        weeklyRest(period, sorted, result);
        sundays(period, sorted, result);
        compensation(period, sorted, days, result);
        daysOff(period, sorted, result);
        if (periodNorm != null && periodWorked > periodNorm) {
            result.add(new Warning(null, "Okres o " + hours(periodWorked - periodNorm) + " h ponad wymiar (przepracowane i zaplanowane)",
                    "art. 129 § 1, art. 130 KP"));
        }
        return result;
    }

    /**
     * Zlecenie u własnego pracodawcy obok umowy o pracę. Dzień z obiema umowami: przy tej samej pracy, w tym samym
     * miejscu i pod tym samym kierownictwem to praca w godzinach nadliczbowych, bez względu na nazwę umowy (art. 22
     * § 1¹ i § 1² KP). Odpoczynek dobowy liczy się z godzin obu umów razem: zmiany, które się stykają albo nakładają,
     * są jednym ciągiem pracy.
     *
     * @param employment zmiany na umowie o pracę (bez dni nieobecności)
     * @param civil zmiany na zleceniu
     */
    public static List<Warning> civilWarnings(List<Shift> employment, List<Shift> civil) {
        List<Warning> result = new ArrayList<>();
        Map<LocalDate, Shift> employmentByDay = employment.stream()
                .collect(Collectors.toMap(Shift::day, Function.identity(), (first, second) -> first));
        for (Shift shift : civil.stream().sorted(Comparator.comparing(Shift::day)).toList()) {
            Shift other = employmentByDay.get(shift.day());
            if (other != null) {
                result.add(new Warning(shift.day(), "Umowa o pracę i zlecenie tego dnia: razem "
                        + hours(other.minutes() + shift.minutes())
                        + " h. Przy tej samej pracy to godziny nadliczbowe, nie zlecenie",
                        "art. 22 § 1¹ i § 1², art. 151 § 1 KP"));
            }
        }
        record Span(LocalDateTime start, LocalDateTime end, boolean civil) {
        }
        List<Span> spans = new ArrayList<>();
        Stream.concat(employment.stream().map(shift -> new Span(shift.startsAt(), shift.endsAt(), false)),
                civil.stream().map(shift -> new Span(shift.startsAt(), shift.endsAt(), true)))
                .sorted(Comparator.comparing(Span::start)).forEach(span -> {
                    Span last = spans.isEmpty() ? null : spans.getLast();
                    if (last != null && !span.start().isAfter(last.end())) {
                        spans.set(spans.size() - 1, new Span(last.start(),
                                span.end().isAfter(last.end()) ? span.end() : last.end(), last.civil() || span.civil()));
                    } else {
                        spans.add(span);
                    }
                });
        for (int index = 1; index < spans.size(); index++) {
            Span previous = spans.get(index - 1);
            Span span = spans.get(index);
            long rest = Duration.between(previous.end(), span.start()).toMinutes();
            // Sam odpoczynek między zmianami umowy o pracę sprawdza już reguła z art. 132.
            if (rest < DAILY_REST_MINUTES && (previous.civil() || span.civil())) {
                result.add(new Warning(span.start().toLocalDate(), "Z godzinami zlecenia odpoczynek " + hours(rest)
                        + " h, a powinno być co najmniej 11 h", "art. 132 § 1, art. 22 § 1¹ KP"));
            }
        }
        return result;
    }

    /** Święto zamknięte to wybór sklepu, nie zakaz: sklepy korzystają z art. 6 ust. 1 pkt 10 ustawy o ograniczeniu handlu. */
    private static void closedDay(Shift shift, Days days, List<Warning> result) {
        LocalDate day = shift.day();
        if (ScheduleCalendar.isClosed(day) && !days.openHoliday().test(day)) {
            result.add(new Warning(day, ScheduleCalendar.holiday(day) + ": sklep oznaczony jako zamknięty",
                    "ustawienie sklepu"));
        }
    }

    private static void betweenShifts(Shift previous, Shift shift, Function<LocalDate, Integer> dailyLimit,
            boolean flexibleTime, List<Warning> result) {
        long rest = Duration.between(previous.endsAt(), shift.startsAt()).toMinutes();
        if (rest < DAILY_REST_MINUTES) {
            result.add(new Warning(shift.day(), "Odpoczynek " + hours(rest) + " h, a powinno być co najmniej 11 h",
                    "art. 132 § 1 KP"));
        }
        // Doba pracownicza liczy się od godziny rozpoczęcia pracy (art. 128 § 3 pkt 1): wcześniejszy start następnego dnia
        // wpada w tę samą dobę, nawet gdy obie zmiany mają po 8 godzin. Przy ruchomym czasie pracy to nie są nadgodziny
        // (art. 140¹ § 3 KP), więc ostrzeżenia nie ma; odpoczynek 11 h sprawdzamy dalej.
        LocalDateTime dobaEnd = previous.startsAt().plusDays(1);
        if (!flexibleTime && shift.startsAt().isBefore(dobaEnd)) {
            LocalDateTime until = shift.endsAt().isBefore(dobaEnd) ? shift.endsAt() : dobaEnd;
            long inDoba = previous.minutes() + Duration.between(shift.startsAt(), until).toMinutes();
            int limit = dailyLimit.apply(previous.day());
            if (inDoba > limit) {
                result.add(new Warning(shift.day(), "Wcześniejszy start: w tej samej dobie " + hours(inDoba) + " h pracy",
                        "art. 128 § 3 pkt 1, art. 129 § 1 KP"));
            }
        }
    }

    /**
     * Tydzień to 7 dni od pierwszego dnia okresu, nie od poniedziałku (art. 128 § 3 pkt 2). Przerwę liczymy w całości, także
     * gdy przechodzi przez granicę tygodnia: okres od niedzieli przeciąłby wolny weekend zwykłego tygodnia pon.-pt. na 30
     * i 34 godziny, a przepis nie mówi, że odpoczynek ma się zmieścić w jednym tygodniu. Tydzień bez zmian jest w porządku.
     */
    private static void weeklyRest(Period period, List<Shift> shifts, List<Warning> result) {
        for (LocalDate from = period.start(); !from.plusDays(6).isAfter(period.end()); from = from.plusWeeks(1)) {
            LocalDate to = from.plusDays(6);
            long longest = 0;
            Set<LocalTime> starts = new HashSet<>();
            boolean worked = false;
            for (int index = 0; index < shifts.size(); index++) {
                Shift shift = shifts.get(index);
                if (shift.day().isBefore(from) || shift.day().isAfter(to)) {
                    continue;
                }
                worked = true;
                starts.add(shift.start());
                longest = Math.max(longest, index == 0 ? Long.MAX_VALUE
                        : Duration.between(shifts.get(index - 1).endsAt(), shift.startsAt()).toMinutes());
                longest = Math.max(longest, index == shifts.size() - 1 ? Long.MAX_VALUE
                        : Duration.between(shift.endsAt(), shifts.get(index + 1).startsAt()).toMinutes());
            }
            long required = starts.size() > 1 ? SHORT_WEEKLY_REST_MINUTES : WEEKLY_REST_MINUTES;
            if (worked && longest < required) {
                result.add(new Warning(from, "Tydzień od " + from.format(DATE) + ": najdłuższy odpoczynek " + hours(longest)
                        + " h, a powinno być co najmniej " + hours(required) + " h", "art. 133 § 1 i 2 KP"));
            }
        }
    }

    private static void sundays(Period period, List<Shift> shifts, List<Warning> result) {
        Set<LocalDate> worked = shifts.stream().map(Shift::day).filter(ScheduleCalendar::isSunday)
                .collect(Collectors.toCollection(HashSet::new));
        for (LocalDate sunday : worked) {
            boolean fourth = period.contains(sunday);
            for (int back = 1; back < SUNDAYS_IN_ROW; back++) {
                fourth = fourth && worked.contains(sunday.minusWeeks(back));
            }
            if (fourth) {
                result.add(new Warning(sunday, "Bez wolnej niedzieli przez 4 tygodnie", "art. 151¹² KP"));
            }
        }
        List<LocalDate> beforeChristmas = ScheduleCalendar.sundaysBeforeChristmas(period.start().getYear()).stream()
                .filter(worked::contains).sorted().toList();
        if (beforeChristmas.size() > MAX_SUNDAYS_BEFORE_CHRISTMAS && period.contains(beforeChristmas.getLast())) {
            result.add(new Warning(beforeChristmas.getLast(), "Praca w trzech niedzielach przed Wigilią, można w dwóch",
                    "art. 7 ust. 1b ustawy o ograniczeniu handlu"));
        }
    }

    /**
     * Każda niedziela i święto z pracą ma mieć swoje WN: za niedzielę w ciągu 6 dni przed nią albo po niej, za święto w okresie
     * rozliczeniowym (art. 151(11) § 1; święto w niedzielę liczy się jak niedziela, § 4). Później niż 6 dni to § 2: dzień do
     * końca okresu tylko wtedy, gdy w terminie się nie dało, więc ostrzegamy. Sklepów jest kilka, a grafik jest po to, żeby
     * to było widać, więc nie zgadujemy, który wolny dzień jest za co: liczy się tylko oznaczenie.
     */
    private static void compensation(Period period, List<Shift> shifts, Days days, List<Warning> result) {
        Set<LocalDate> worked = shifts.stream().map(Shift::day).collect(Collectors.toSet());
        Map<LocalDate, LocalDate> offByWorked = days.compensating().stream()
                .collect(Collectors.toMap(DayOff::forDate, DayOff::day, (first, second) -> first));
        worked.stream().filter(period::contains).filter(ScheduleRules::needsDayOff).sorted().forEach(day -> {
            boolean sunday = ScheduleCalendar.isSunday(day);
            String name = (sunday ? "Niedziela " : "Święto ") + day.format(DATE);
            String basis = sunday ? "art. 151¹¹ § 1 pkt 1 KP" : "art. 151¹¹ § 1 pkt 2 KP";
            LocalDate off = offByWorked.get(day);
            if (off == null) {
                result.add(new Warning(day, name + ": brak WN", basis));
            } else if (!period.contains(off) && (!sunday || distance(day, off) > SUNDAY_DAY_OFF_DAYS)) {
                result.add(new Warning(day, name + ": WN poza okresem rozliczeniowym", basis));
            } else if (sunday && distance(day, off) > SUNDAY_DAY_OFF_DAYS) {
                result.add(new Warning(day, name + ": WN dalej niż 6 dni", "art. 151¹¹ § 2 KP"));
            }
        });
        for (DayOff off : days.compensating()) {
            if (period.contains(off.day()) && !worked.contains(off.forDate())) {
                result.add(new Warning(off.day(), "WN za " + off.forDate().format(DATE) + ", a tego dnia nie ma zmiany",
                        "art. 151¹¹ § 1 KP"));
            }
        }
    }

    /** Dzień, za którego przepracowanie należy się WN. */
    public static boolean needsDayOff(LocalDate day) {
        return ScheduleCalendar.isSunday(day) || ScheduleCalendar.holiday(day) != null;
    }

    private static long distance(LocalDate first, LocalDate second) {
        return Math.abs(ChronoUnit.DAYS.between(first, second));
    }

    /** Dni pracy nie mogą zjeść dni wolnych należnych w okresie: zostaje ich tyle, ile dni pełnego wymiaru (art. 147). */
    private static void daysOff(Period period, List<Shift> shifts, List<Warning> result) {
        int fullDays = period.days().stream().mapToInt(ScheduleCalendar::normMinutes).sum() / (8 * 60);
        long workDays = shifts.stream().filter(shift -> period.contains(shift.day())).count();
        if (workDays > fullDays) {
            result.add(new Warning(null, workDays + " dni pracy w okresie, można najwyżej " + fullDays, "art. 147 KP"));
        }
    }

    /** 450 → 7,5; 480 → 8. */
    private static String hours(long minutes) {
        return minutes % 60 == 0 ? String.valueOf(minutes / 60)
                : String.valueOf(Math.round(minutes / 6.0) / 10.0).replace('.', ',');
    }
}
