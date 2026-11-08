package com.intermanage.backend.staff.component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;

/**
 * Kalendarz grafiku: okres rozliczeniowy, święta (art. 1 ustawy o dniach wolnych od pracy, Dz.U. 2025 poz. 296), niedziele
 * przed Wigilią (art. 7 ustawy o ograniczeniu handlu, Dz.U. 2025 poz. 301) i wymiar czasu pracy (art. 130 KP, Dz.U. 2026
 * poz. 1245). Sklepy działają jako klub książki (art. 6 ust. 1 pkt 10 ustawy o ograniczeniu handlu), więc zakaz z art. 5 i 8
 * ich nie obejmuje: niedziela jest zwykłym dniem grafiku, a święto jest zamknięte z wyboru sklepu. Zmiana przepisów = zmiana
 * tej klasy i {@code ScheduleLawTests}.
 */
public final class ScheduleCalendar {

    /** Okres rozliczeniowy firmy: 3 miesiące, pierwszy od lutego. */
    private static final int PERIOD_MONTHS = 3;
    private static final int FIRST_PERIOD_MONTH = Month.FEBRUARY.getValue();
    private static final int FULL_DAY_MINUTES = 8 * 60;

    private ScheduleCalendar() {
    }

    public record Period(LocalDate start, LocalDate end) {

        public boolean contains(LocalDate day) {
            return !day.isBefore(start) && !day.isAfter(end);
        }

        public List<LocalDate> days() {
            return start.datesUntil(end.plusDays(1)).toList();
        }
    }

    /** Luty-kwiecień, maj-lipiec, sierpień-październik, listopad-styczeń. */
    public static Period period(LocalDate day) {
        int monthsSinceStart = Math.floorMod(day.getMonthValue() - FIRST_PERIOD_MONTH, PERIOD_MONTHS);
        LocalDate start = day.withDayOfMonth(1).minusMonths(monthsSinceStart);
        return new Period(start, start.plusMonths(PERIOD_MONTHS).minusDays(1));
    }

    /** Nazwa święta albo {@code null}. Zwykłe niedziele nie są tu świętem. */
    public static String holiday(LocalDate day) {
        LocalDate easter = easter(day.getYear());
        if (day.equals(easter)) {
            return "Wielkanoc";
        }
        if (day.equals(easter.plusDays(1))) {
            return "Poniedziałek Wielkanocny";
        }
        if (day.equals(easter.plusDays(49))) {
            return "Zielone Świątki";
        }
        if (day.equals(easter.plusDays(60))) {
            return "Boże Ciało";
        }
        return switch (day.getMonth()) {
            case JANUARY -> day.getDayOfMonth() == 1 ? "Nowy Rok" : day.getDayOfMonth() == 6 ? "Trzech Króli" : null;
            case MAY -> day.getDayOfMonth() == 1 ? "Święto Państwowe"
                    : day.getDayOfMonth() == 3 ? "Święto Konstytucji 3 Maja" : null;
            case AUGUST -> day.getDayOfMonth() == 15 ? "Wniebowzięcie NMP" : null;
            case NOVEMBER -> day.getDayOfMonth() == 1 ? "Wszystkich Świętych"
                    : day.getDayOfMonth() == 11 ? "Święto Niepodległości" : null;
            case DECEMBER -> switch (day.getDayOfMonth()) {
                case 24 -> "Wigilia";
                case 25 -> "Boże Narodzenie";
                case 26 -> "Drugi dzień Bożego Narodzenia";
                default -> null;
            };
            default -> null;
        };
    }

    public static boolean isSunday(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** Trzy niedziele przed Wigilią; pracownik może pracować najwyżej w dwóch z nich (art. 7 ust. 1b). */
    public static Set<LocalDate> sundaysBeforeChristmas(int year) {
        LocalDate last = LocalDate.of(year, Month.DECEMBER, 24).with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        return Set.of(last, last.minusWeeks(1), last.minusWeeks(2));
    }

    /** Święto, także w niedzielę. Zwykłe niedziele sklep ma czynne. */
    public static boolean isClosed(LocalDate day) {
        return holiday(day) != null;
    }

    /**
     * Udział dnia w wymiarze przy pełnym etacie, w minutach. Art. 130 § 1 to 40 godzin na tydzień i 8 na resztę dni
     * pon.-pt., czyli 8 godzin za każdy taki dzień, a § 2 odejmuje 8 godzin za święto poza niedzielą, także w sobotę
     * (stąd wynik ujemny). Dzięki temu wymiar można sumować po dniach, z etatem obowiązującym danego dnia.
     */
    public static int normMinutes(LocalDate day) {
        int work = day.getDayOfWeek().compareTo(DayOfWeek.FRIDAY) <= 0 ? FULL_DAY_MINUTES : 0;
        return holiday(day) != null && !isSunday(day) ? work - FULL_DAY_MINUTES : work;
    }

    /** Algorytm Meeusa/Jonesa/Butchera. */
    private static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
