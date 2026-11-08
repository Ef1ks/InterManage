package com.intermanage.backend.staff.component;

import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.staff.component.StaffFile.Fte;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffLeave;

/**
 * Wymiar urlopu w roku kalendarzowym. Podstawa art. 154 KP (20 albo 26 dni), niepełny etat proporcjonalnie (par. 2),
 * część roku proporcjonalnie po 1/12 za miesiąc kalendarzowy z zaokrągleniem miesiąca i dnia w górę (art. 155(1),
 * 155(2a), 155(3)), pierwsza praca co miesiąc (art. 153 par. 1). Urlop dodatkowy: art. 19 ustawy o rehabilitacji.
 */
public final class LeaveCalculator {

    private static final int BASE_DAYS = 20;
    private static final int HIGHER_DAYS = 26;
    private static final int ADDITIONAL_DAYS = 10;

    private LeaveCalculator() {
    }

    /** Pozostałe dni mogą być ujemne, gdy wykorzystano więcej, niż przysługuje. */
    public record LeaveYear(int year, int fullDays, int annualDays, int additionalDays, int carriedDays, int usedDays,
            int remainingDays, Map<LeaveKind, Integer> usage) {
    }

    public static LeaveYear forYear(StaffFile file, int year, LocalDate today) {
        int carried = 0;
        for (int earlier = firstYear(file, year); earlier < year; earlier++) {
            carried = Math.max(0, carried + opening(file, earlier) + entitlement(file, earlier, today).total()
                    - usedAnnual(file, earlier));
        }
        carried += opening(file, year);
        Entitlement entitlement = entitlement(file, year, today);
        int used = usedAnnual(file, year);
        return new LeaveYear(year, entitlement.fullDays(), entitlement.annualDays(), entitlement.additionalDays(),
                carried, used, carried + entitlement.total() - used, usage(file, year));
    }

    /** Urlop zaległy, który po 30 września tego roku jest po terminie: dni z poprzednich lat minus zużyte do tego dnia. */
    public static int overdueDays(StaffFile file, int year, LocalDate today) {
        LeaveYear leave = forYear(file, year, today);
        LocalDate deadline = LocalDate.of(year, 9, 30);
        int usedBefore = file.leaves().stream()
                .filter(entry -> entry.getKind().countsAsAnnual() && entry.getStartDate().getYear() == year
                        && !entry.getStartDate().isAfter(deadline))
                .mapToInt(StaffLeave::getDays).sum();
        return today.isAfter(deadline) ? Math.max(0, leave.carriedDays() - usedBefore) : 0;
    }

    private record Entitlement(int fullDays, int annualDays, int additionalDays) {

        int total() {
            return annualDays + additionalDays;
        }
    }

    private static Entitlement entitlement(StaffFile file, int year, LocalDate today) {
        List<StaffContract> contracts = file.employment();
        Map<Fraction, Integer> months = monthsByFte(file, contracts, year);
        if (months.isEmpty()) {
            return new Entitlement(0, 0, 0);
        }
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate lastDay = lastEmploymentDay(contracts, yearEnd);
        int full = Seniority.forLeave(file, lastDay).years() >= Seniority.HIGHER_LEAVE_YEARS ? HIGHER_DAYS : BASE_DAYS;

        boolean firstJobYear = file.profile() != null && file.profile().isFirstJob()
                && contracts.getFirst().getStartDate().getYear() == year;
        int annual = firstJobYear ? firstJobDays(file, contracts.getFirst(), full, lastDay, today)
                : Math.min(full, proportional(full, months));
        return new Entitlement(full, annual, Math.min(ADDITIONAL_DAYS, additional(file, year, months, yearEnd)));
    }

    /** Art. 153 par. 1: z upływem każdego miesiąca pracy 1/12, więc liczą się tylko miesiące, które już minęły. */
    private static int firstJobDays(StaffFile file, StaffContract first, int full, LocalDate lastDay, LocalDate today) {
        LocalDate until = (today.isBefore(lastDay) ? today : lastDay).plusDays(1);
        if (until.isBefore(first.getStartDate())) {
            return 0;
        }
        int months = (int) Period.between(first.getStartDate(), until).toTotalMonths();
        Map<Fraction, Integer> byFte = new TreeMap<>();
        for (int month = 0; month < months; month++) {
            Fte fte = file.fteOn(first, first.getStartDate().plusMonths(month + 1L).minusDays(1));
            byFte.merge(new Fraction(fte.numerator(), fte.denominator()), 1, Integer::sum);
        }
        return Math.min(full, proportional(full, byFte));
    }

    /** Art. 19: prawo po przepracowaniu roku po dniu zaliczenia do stopnia; lekki stopień go nie daje. */
    private static int additional(StaffFile file, int year, Map<Fraction, Integer> months, LocalDate yearEnd) {
        List<StaffContract> contracts = file.employment();
        LocalDate firstWork = contracts.getFirst().getStartDate();
        boolean entitled = file.disabilities().stream()
                .filter(StaffDisability::isSignificant)
                .anyMatch(disability -> {
                    LocalDate from = disability.getStartDate().isAfter(firstWork) ? disability.getStartDate() : firstWork;
                    LocalDate right = from.plusYears(1);
                    return !right.isAfter(yearEnd)
                            && (disability.getEndDate() == null || !disability.getEndDate().isBefore(right))
                            && (disability.getEndDate() == null || disability.getEndDate().getYear() >= year);
                });
        return entitled ? proportional(ADDITIONAL_DAYS, months) : 0;
    }

    private static int proportional(int days, Map<Fraction, Integer> months) {
        int result = 0;
        for (Map.Entry<Fraction, Integer> entry : months.entrySet()) {
            Fraction fte = entry.getKey();
            result += ceil((long) days * fte.numerator() * entry.getValue(), (long) fte.denominator() * 12);
        }
        return result;
    }

    /**
     * Miesiące kalendarzowe z umową o pracę według etatu; miesiąc z choćby jednym dniem liczy się w całości, z etatem z
     * jego ostatniego dnia pracy. Aneks w trakcie roku dzieli więc rok na części z własnym etatem.
     */
    private static Map<Fraction, Integer> monthsByFte(StaffFile file, List<StaffContract> contracts, int year) {
        Map<Fraction, Integer> result = new TreeMap<>();
        for (int month = 1; month <= 12; month++) {
            YearMonth current = YearMonth.of(year, month);
            StaffContract latest = null;
            for (StaffContract contract : contracts) {
                boolean active = !contract.getStartDate().isAfter(current.atEndOfMonth())
                        && (contract.getEndDate() == null || !contract.getEndDate().isBefore(current.atDay(1)));
                if (active) {
                    latest = contract;
                }
            }
            if (latest != null) {
                LocalDate lastDay = latest.getEndDate() != null && latest.getEndDate().isBefore(current.atEndOfMonth())
                        ? latest.getEndDate() : current.atEndOfMonth();
                Fte fte = file.fteOn(latest, lastDay);
                result.merge(new Fraction(fte.numerator(), fte.denominator()), 1, Integer::sum);
            }
        }
        return result;
    }

    private static LocalDate lastEmploymentDay(List<StaffContract> contracts, LocalDate yearEnd) {
        LocalDate last = null;
        for (StaffContract contract : contracts) {
            if (contract.getStartDate().isAfter(yearEnd)) {
                continue;
            }
            LocalDate end = contract.getEndDate() == null || contract.getEndDate().isAfter(yearEnd) ? yearEnd
                    : contract.getEndDate();
            last = last == null || end.isAfter(last) ? end : last;
        }
        return last;
    }

    /**
     * Rok, od którego liczy się pula. Bez stanu początkowego nie sumujemy lat sprzed założenia kartoteki: nie ma wpisów,
     * ile wtedy wykorzystano, więc wyszłyby dni, których pracownik nie ma.
     */
    private static int firstYear(StaffFile file, int year) {
        if (file.opening() != null) {
            return Math.min(year, file.opening().getYear());
        }
        List<StaffContract> contracts = file.employment();
        if (contracts.isEmpty()) {
            return year;
        }
        int first = contracts.getFirst().getStartDate().getYear();
        int recorded = contracts.stream().map(StaffContract::getCreatedAt).filter(Objects::nonNull)
                .mapToInt(created -> created.atZone(StoreTime.ZONE).getYear()).min().orElse(first);
        return Math.min(year, Math.max(first, recorded));
    }

    /** Umowa starsza niż kartoteka, a nie podano, ile urlopu zostało z tamtych lat. */
    public static boolean missesOpening(StaffFile file, int year) {
        List<StaffContract> contracts = file.employment();
        return file.opening() == null && !contracts.isEmpty()
                && contracts.getFirst().getStartDate().getYear() < firstYear(file, year);
    }

    private static int opening(StaffFile file, int year) {
        return file.opening() != null && file.opening().getYear() == year ? file.opening().getDays() : 0;
    }

    private static int usedAnnual(StaffFile file, int year) {
        return file.leaves().stream()
                .filter(entry -> entry.getKind().countsAsAnnual() && entry.getStartDate().getYear() == year)
                .mapToInt(StaffLeave::getDays).sum();
    }

    private static Map<LeaveKind, Integer> usage(StaffFile file, int year) {
        Map<LeaveKind, Integer> result = new EnumMap<>(LeaveKind.class);
        new ArrayList<>(file.leaves()).stream()
                .filter(entry -> entry.getStartDate().getYear() == year)
                .forEach(entry -> result.merge(entry.getKind(), entry.getDays(), Integer::sum));
        return result;
    }

    private static int ceil(long numerator, long denominator) {
        return (int) ((numerator + denominator - 1) / denominator);
    }

    private record Fraction(int numerator, int denominator) implements Comparable<Fraction> {

        @Override
        public int compareTo(Fraction other) {
            int order = Integer.compare(numerator * other.denominator, other.numerator * denominator);
            return order != 0 ? order : Integer.compare(denominator, other.denominator);
        }
    }
}
