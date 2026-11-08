package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.staff.component.PeriodBalance;
import com.intermanage.backend.staff.component.ScheduleCalendar;
import com.intermanage.backend.staff.component.ScheduleCalendar.Period;
import com.intermanage.backend.staff.component.ScheduleRules;
import com.intermanage.backend.staff.component.ScheduleRules.DayOff;
import com.intermanage.backend.staff.component.ScheduleRules.Days;
import com.intermanage.backend.staff.component.ScheduleRules.Shift;
import com.intermanage.backend.staff.component.ScheduleRules.Warning;

/** Kalendarz i reguły grafiku na przykładach z przepisów, bez bazy. */
class ScheduleLawTests {

    private static final Period NOV_JAN = ScheduleCalendar.period(LocalDate.of(2026, 12, 15));
    private static final int EIGHT_HOURS = 8 * 60;

    @Test
    void periodsStartInFebruary() {
        assertThat(ScheduleCalendar.period(LocalDate.of(2026, 2, 1)))
                .isEqualTo(new Period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 30)));
        assertThat(ScheduleCalendar.period(LocalDate.of(2026, 7, 31)))
                .isEqualTo(new Period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 31)));
        assertThat(ScheduleCalendar.period(LocalDate.of(2026, 8, 1)).end()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(NOV_JAN).isEqualTo(new Period(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31)));
        assertThat(ScheduleCalendar.period(LocalDate.of(2027, 1, 31)).start()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void holidaysFromTheAct() {
        assertThat(ScheduleCalendar.holiday(LocalDate.of(2026, 12, 24))).isEqualTo("Wigilia");
        assertThat(ScheduleCalendar.holiday(LocalDate.of(2026, 4, 5))).isEqualTo("Wielkanoc");
        assertThat(ScheduleCalendar.holiday(LocalDate.of(2026, 4, 6))).isEqualTo("Poniedziałek Wielkanocny");
        assertThat(ScheduleCalendar.holiday(LocalDate.of(2026, 5, 24))).isEqualTo("Zielone Świątki");
        assertThat(ScheduleCalendar.holiday(LocalDate.of(2026, 6, 4))).isEqualTo("Boże Ciało");
        assertThat(ScheduleCalendar.holiday(LocalDate.of(2026, 11, 12))).isNull();
    }

    @Test
    void normFollowsArticle130() {
        // Listopad 2026: 21 dni pon.-pt., 11.11 w środę; 1.11 w niedzielę nie obniża wymiaru.
        assertThat(monthHours(2026, 11)).isEqualTo(160);
        // Grudzień 2026: 23 dni pon.-pt., 24 i 25 w tygodniu, 26 w sobotę obniża tak samo (art. 130 § 2).
        assertThat(monthHours(2026, 12)).isEqualTo(160);
        // Styczeń 2027: 21 dni pon.-pt., 1 i 6 w tygodniu.
        assertThat(monthHours(2027, 1)).isEqualTo(152);
        assertThat(NOV_JAN.days().stream().mapToInt(ScheduleCalendar::normMinutes).sum() / 60).isEqualTo(472);
    }

    @Test
    void storeIsOpenOnSundaysAndClosedOnHolidays() {
        assertThat(ScheduleCalendar.isClosed(LocalDate.of(2026, 11, 8))).isFalse();
        assertThat(ScheduleCalendar.isClosed(LocalDate.of(2026, 11, 9))).isFalse();
        assertThat(ScheduleCalendar.isClosed(LocalDate.of(2026, 11, 11))).isTrue();
        // Święto w niedzielę zostaje świętem: 1.11.2026 i Wielkanoc.
        assertThat(ScheduleCalendar.isClosed(LocalDate.of(2026, 11, 1))).isTrue();
        assertThat(ScheduleCalendar.isClosed(LocalDate.of(2026, 4, 5))).isTrue();
    }

    @Test
    void regularWeekHasNoWarnings() {
        List<Shift> shifts = new ArrayList<>();
        for (int day = 2; day <= 6; day++) {
            shifts.add(shift(LocalDate.of(2026, 11, day), 6, 14));
        }
        assertThat(check(shifts, 472 * 60)).isEmpty();
    }

    @Test
    void shiftOverDailyLimit() {
        assertThat(messages(List.of(shift(LocalDate.of(2026, 11, 3), 6, 15)))).containsExactly("Zmiana dłuższa niż 8 h");
        assertThat(ScheduleRules.check(NOV_JAN, List.of(shift(LocalDate.of(2026, 11, 3), 6, 14)), day -> 7 * 60, null, 0, Days.NONE))
                .extracting(Warning::message).containsExactly("Zmiana dłuższa niż 7 h");
    }

    @Test
    void dailyRestOfElevenHours() {
        List<Shift> shifts = List.of(shift(LocalDate.of(2026, 11, 3), 14, 22), shift(LocalDate.of(2026, 11, 4), 6, 14));
        assertThat(messages(shifts)).contains("Odpoczynek 8 h, a powinno być co najmniej 11 h");
    }

    @Test
    void earlierStartFallsIntoTheSameDoba() {
        // 8-16 i następnego dnia 6-14: 2 godziny drugiej zmiany wpadają w dobę pierwszej, odpoczynek ma 14 h.
        List<Shift> shifts = List.of(shift(LocalDate.of(2026, 11, 3), 8, 16), shift(LocalDate.of(2026, 11, 4), 6, 14));
        assertThat(messages(shifts)).containsExactly("Wcześniejszy start: w tej samej dobie 10 h pracy");
    }

    @Test
    void nightShiftCrossesMidnight() {
        Shift night = shift(LocalDate.of(2026, 11, 3), 22, 6);
        assertThat(night.minutes()).isEqualTo(EIGHT_HOURS);
        assertThat(messages(List.of(night, shift(LocalDate.of(2026, 11, 4), 14, 22))))
                .contains("Odpoczynek 8 h, a powinno być co najmniej 11 h");
    }

    @Test
    void weeklyRestCountsWeeksFromPeriodStart() {
        // Tydzień okresu listopad-styczeń zaczyna się w niedzielę 1.11. Praca 2-7.11 zostawia wolną niedzielę 1.11.
        List<Shift> sixDays = new ArrayList<>();
        for (int day = 2; day <= 7; day++) {
            sixDays.add(shift(LocalDate.of(2026, 11, day), 6, 14));
        }
        assertThat(messages(sixDays)).isEmpty();

        // Praca 5-13.12 nie daje w tygodniu 6-12.12 żadnej dłuższej przerwy.
        List<Shift> nineDays = new ArrayList<>();
        for (int day = 5; day <= 13; day++) {
            nineDays.add(shift(LocalDate.of(2026, 12, day), 6, 14));
        }
        assertThat(messages(nineDays)).contains("Tydzień od 06.12: najdłuższy odpoczynek 16 h, a powinno być co najmniej 35 h");
    }

    @Test
    void shiftChangeAllowsShorterWeeklyRest() {
        // Art. 133 § 2: przy przejściu na inną zmianę tygodniowy odpoczynek może mieć 24 h, ale nie mniej.
        List<Shift> shifts = new ArrayList<>();
        for (int day = 9; day <= 13; day++) {
            shifts.add(shift(LocalDate.of(2026, 11, day), 14, 22));
        }
        shifts.add(shift(LocalDate.of(2026, 11, 15), 6, 14));
        shifts.add(shift(LocalDate.of(2026, 11, 16), 6, 14));
        assertThat(messages(shifts)).noneMatch(message -> message.contains("najdłuższy odpoczynek"));
    }

    @Test
    void closedHolidayWarnsButSundayDoesNot() {
        assertThat(messages(List.of(shift(LocalDate.of(2026, 11, 11), 6, 14))))
                .contains("Święto Niepodległości: sklep oznaczony jako zamknięty");
        assertThat(messages(List.of(shift(LocalDate.of(2026, 11, 8), 6, 14))))
                .noneMatch(message -> message.contains("zamknięty"));
    }

    @Test
    void atMostTwoSundaysBeforeChristmas() {
        List<Shift> shifts = List.of(shift(LocalDate.of(2026, 12, 6), 6, 14), shift(LocalDate.of(2026, 12, 13), 6, 14),
                shift(LocalDate.of(2026, 12, 20), 6, 14));
        assertThat(messages(shifts)).contains("Praca w trzech niedzielach przed Wigilią, można w dwóch");
        assertThat(messages(shifts.subList(0, 2))).noneMatch(message -> message.contains("trzech"));
    }

    @Test
    void freeSundayEveryFourWeeks() {
        Period period = ScheduleCalendar.period(LocalDate.of(2026, 6, 1));
        List<Shift> shifts = List.of(shift(LocalDate.of(2026, 6, 7), 6, 14), shift(LocalDate.of(2026, 6, 14), 6, 14),
                shift(LocalDate.of(2026, 6, 21), 6, 14), shift(LocalDate.of(2026, 6, 28), 6, 14));
        assertThat(ScheduleRules.check(period, shifts, day -> EIGHT_HOURS, null, 0, Days.NONE)).extracting(Warning::message)
                .contains("Bez wolnej niedzieli przez 4 tygodnie");
    }

    @Test
    void planOverNorm() {
        assertThat(ScheduleRules.check(NOV_JAN, List.of(), day -> EIGHT_HOURS, 472 * 60, 480 * 60, Days.NONE))
                .extracting(Warning::message).containsExactly("Okres o 8 h ponad wymiar (przepracowane i zaplanowane)");
        assertThat(ScheduleRules.check(NOV_JAN, List.of(), day -> EIGHT_HOURS, null, 480 * 60, Days.NONE)).isEmpty();
    }

    /** Zlecenie tego samego dnia co umowa o pracę: ostrzeżenie, a odpoczynek liczy się z obu umów razem. */
    @Test
    void civilHoursNextToEmployment() {
        LocalDate monday = LocalDate.of(2026, 12, 7);
        List<Shift> employment = List.of(shift(monday, 6, 14), shift(monday.plusDays(1), 6, 14));
        List<Warning> sameDay = ScheduleRules.civilWarnings(employment, List.of(shift(monday, 14, 18)));
        assertThat(sameDay).extracting(Warning::message)
                .containsExactly("Umowa o pracę i zlecenie tego dnia: razem 12 h. Przy tej samej pracy to godziny "
                        + "nadliczbowe, nie zlecenie");
        // 14-22 na zleceniu i 6:00 następnego dnia: 8 h odpoczynku.
        assertThat(ScheduleRules.civilWarnings(employment, List.of(shift(monday, 14, 22))))
                .extracting(Warning::message).contains("Z godzinami zlecenia odpoczynek 8 h, a powinno być co najmniej 11 h");
        // Zlecenie w dzień wolny od umowy o pracę, z pełnym odpoczynkiem: bez ostrzeżeń.
        assertThat(ScheduleRules.civilWarnings(employment, List.of(shift(monday.plusDays(3), 10, 14)))).isEmpty();
    }

    /** Za dni z kartą liczy się czas z karty, nie plan; nadgodziny dobowe są poza saldem. */
    @Test
    void periodBalanceUsesTimeCards() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        PeriodBalance.Sum sum = PeriodBalance.sum(List.of(
                new PeriodBalance.Day(monday, 480, 360, 480, EIGHT_HOURS),
                new PeriodBalance.Day(monday.plusDays(1), 480, 480, 600, EIGHT_HOURS),
                new PeriodBalance.Day(monday.plusDays(2), 480, 480, null, EIGHT_HOURS),
                new PeriodBalance.Day(monday.plusDays(3), 480, null, null, EIGHT_HOURS)));
        // 8 h zamiast planowanych 6, 10 h (2 h dobowych nadgodzin poza saldem), 8 h z planu, dzień wolny.
        assertThat(sum).isEqualTo(new PeriodBalance.Sum(480 + 480 + 480, 4 * 480, 120));
        assertThat(sum.balance()).isEqualTo(-480);
    }

    /**
     * Nadwyżka z listopada obniża cel grudnia. Pusty styczeń nie zrzuca swojego wymiaru na grudzień, a styczeń
     * zaplanowany ponad wymiar obniża cel grudnia.
     */
    @Test
    void monthTargetTakesEarlierExcess() {
        PeriodBalance.Sum november = new PeriodBalance.Sum(168 * 60, 160 * 60, 0);
        PeriodBalance.Sum december = new PeriodBalance.Sum(160 * 60, 160 * 60, 0);
        int carried = PeriodBalance.carried(november, null);
        PeriodBalance.Sum emptyJanuary = new PeriodBalance.Sum(0, 160 * 60, 0);
        assertThat(PeriodBalance.monthTarget(december, carried, emptyJanuary)).isEqualTo(152 * 60);
        PeriodBalance.Sum overJanuary = new PeriodBalance.Sum(164 * 60, 160 * 60, 0);
        assertThat(PeriodBalance.monthTarget(december, carried, overJanuary)).isEqualTo(148 * 60);
        // listopad, pierwszy miesiąc okresu, nic nie ułożone dalej: cel = wymiar listopada
        assertThat(PeriodBalance.monthTarget(november, 0, new PeriodBalance.Sum(0, 320 * 60, 0))).isEqualTo(160 * 60);
    }

    /**
     * Listopad w toku: 7 h przy wymiarze 160 h, reszta jeszcze nieułożona. Niedobór zostaje w listopadzie (da się go
     * nadrobić), do grudnia przechodzi tylko nadwyżka. Zakończony miesiąc przenosi całe saldo.
     */
    @Test
    void carriedTakesDeficitOnlyFromClosedMonths() {
        PeriodBalance.Sum openNovember = new PeriodBalance.Sum(7 * 60, 160 * 60, 0);
        assertThat(PeriodBalance.carried(null, openNovember)).isZero();
        assertThat(PeriodBalance.carried(null, new PeriodBalance.Sum(170 * 60, 160 * 60, 0))).isEqualTo(10 * 60);
        assertThat(PeriodBalance.carried(new PeriodBalance.Sum(150 * 60, 160 * 60, 0), null)).isEqualTo(-10 * 60);
    }

    @Test
    void workDaysCannotEatDaysOff() {
        List<Shift> shifts = NOV_JAN.days().stream().filter(day -> !ScheduleCalendar.isClosed(day))
                .map(day -> shift(day, 6, 14)).toList();
        assertThat(messages(shifts)).anyMatch(message -> message.endsWith("można najwyżej 59"));
    }

    @Test
    void workedSundayNeedsMarkedDayOff() {
        LocalDate sunday = LocalDate.of(2026, 12, 6);
        List<Shift> shifts = List.of(shift(sunday, 6, 14));
        assertThat(messages(shifts)).containsExactly("Niedziela 06.12: brak WN");
        assertThat(withDayOff(shifts, LocalDate.of(2026, 12, 9), sunday)).isEmpty();
        assertThat(withDayOff(shifts, LocalDate.of(2026, 12, 1), sunday)).isEmpty();
        assertThat(withDayOff(shifts, LocalDate.of(2026, 12, 14), sunday)).containsExactly("Niedziela 06.12: WN dalej niż 6 dni");
    }

    @Test
    void sundayAtPeriodEndMayHaveDayOffInNextPeriod() {
        LocalDate sunday = LocalDate.of(2027, 1, 31);
        List<Shift> shifts = List.of(shift(sunday, 6, 14));
        assertThat(withDayOff(shifts, LocalDate.of(2027, 2, 2), sunday)).isEmpty();
        assertThat(withDayOff(shifts, LocalDate.of(2027, 2, 12), sunday))
                .containsExactly("Niedziela 31.01: WN poza okresem rozliczeniowym");
    }

    @Test
    void holidayDayOffAnywhereInPeriod() {
        LocalDate christmas = LocalDate.of(2026, 12, 25);
        Days open = new Days(christmas::equals, List.of());
        List<Shift> shifts = List.of(shift(christmas, 6, 14));
        assertThat(ScheduleRules.check(NOV_JAN, shifts, day -> EIGHT_HOURS, null, 0, open)).extracting(Warning::message)
                .containsExactly("Święto 25.12: brak WN");
        Days marked = new Days(christmas::equals, List.of(new DayOff(LocalDate.of(2027, 1, 20), christmas)));
        assertThat(ScheduleRules.check(NOV_JAN, shifts, day -> EIGHT_HOURS, null, 0, marked)).isEmpty();
        Days outside = new Days(christmas::equals, List.of(new DayOff(LocalDate.of(2027, 2, 2), christmas)));
        assertThat(ScheduleRules.check(NOV_JAN, shifts, day -> EIGHT_HOURS, null, 0, outside)).extracting(Warning::message)
                .containsExactly("Święto 25.12: WN poza okresem rozliczeniowym");
    }

    @Test
    void dayOffForDayWithoutShift() {
        assertThat(withDayOff(List.of(), LocalDate.of(2026, 12, 9), LocalDate.of(2026, 12, 6)))
                .containsExactly("WN za 06.12, a tego dnia nie ma zmiany");
    }

    private static List<String> withDayOff(List<Shift> shifts, LocalDate day, LocalDate forDate) {
        Days days = new Days(date -> true, List.of(new DayOff(day, forDate)));
        return ScheduleRules.check(NOV_JAN, shifts, date -> EIGHT_HOURS, null, 0, days).stream().map(Warning::message).toList();
    }

    private static int monthHours(int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        return first.datesUntil(first.plusMonths(1)).mapToInt(ScheduleCalendar::normMinutes).sum() / 60;
    }

    private static Shift shift(LocalDate day, int from, int to) {
        return new Shift(day, LocalTime.of(from, 0), LocalTime.of(to, 0));
    }

    private static List<Warning> check(List<Shift> shifts, int norm) {
        int worked = shifts.stream().mapToInt(Shift::minutes).sum();
        return ScheduleRules.check(NOV_JAN, shifts, day -> EIGHT_HOURS, norm, worked, Days.NONE);
    }

    private static List<String> messages(List<Shift> shifts) {
        return ScheduleRules.check(NOV_JAN, shifts, day -> EIGHT_HOURS, null, 0, Days.NONE).stream().map(Warning::message).toList();
    }
}
