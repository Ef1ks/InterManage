package com.intermanage.backend.staff.component;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.intermanage.backend.staff.entity.EducationLevel;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffProfile;
import com.intermanage.backend.staff.entity.StaffWorkPeriod;
import com.intermanage.backend.staff.entity.WorkPeriodKind;

/**
 * Staż pracy. Pokrywające się okresy liczą się raz (art. 154(1) par. 2 i art. 302(1) par. 10 KP). Staż urlopowy dolicza
 * lata szkoły (art. 155 par. 1), a nauka w czasie pracy liczy się tym, co korzystniejsze (art. 155 par. 2).
 */
public final class Seniority {

    /**
     * Art. 9 ustawy z 26.09.2025 (Dz.U. 2025 poz. 1423): u pracodawcy spoza sektora finansów publicznych okresy z art.
     * 302(1) KP działają od tego dnia, a uprawnienia z nich nie powstają wcześniej (art. 6).
     */
    public static final LocalDate NEW_PERIODS_FROM = LocalDate.of(2026, 5, 1);

    /** Art. 5: pracownik zatrudniony w dniu {@link #NEW_PERIODS_FROM} ma 24 miesiące na udokumentowanie okresów. */
    public static final LocalDate NEW_PERIODS_DOCUMENT_UNTIL = NEW_PERIODS_FROM.plusMonths(24);

    public static final int HIGHER_LEAVE_YEARS = 10;

    private Seniority() {
    }

    /** Okres od do, obie daty włącznie. */
    public record Span(LocalDate from, LocalDate to) {
    }

    /** Lata, miesiące i dni jak w kadrach: 30 dni to miesiąc, 12 miesięcy to rok. */
    public record Length(int years, int months, int days) {

        static Length ofDays(int total) {
            return new Length(total / 360, total % 360 / 30, total % 30);
        }

        public int totalDays() {
            return years * 360 + months * 30 + days;
        }
    }

    /** Staż pracy na dany dzień, bez szkoły. */
    public static Length work(StaffFile file, LocalDate on) {
        return Length.ofDays(days(merge(spans(file, on))));
    }

    /** Staż w tej firmie: same umowy o pracę. */
    public static Length inCompany(StaffFile file, LocalDate on) {
        return Length.ofDays(days(merge(contractSpans(file, on))));
    }

    /** Staż decydujący o wymiarze urlopu: praca i szkoła. */
    public static Length forLeave(StaffFile file, LocalDate on) {
        List<Span> work = merge(spans(file, on));
        StaffProfile profile = file.profile();
        EducationLevel level = profile == null ? null : profile.getEducationLevel();
        if (level == null || level.years() == 0) {
            return Length.ofDays(days(work));
        }
        int schoolDays = level.years() * 360;
        LocalDate end = profile.getEducationEndDate();
        if (end == null) {
            return Length.ofDays(days(work) + schoolDays);
        }
        Span study = new Span(end.minusYears(level.years()).plusDays(1), end);
        int duringStudy = days(intersect(work, study));
        return Length.ofDays(days(work) - duringStudy + Math.max(duringStudy, schoolDays));
    }

    /** Dzień osiągnięcia 10 lat stażu urlopowego; pusty, gdy nie ma trwającej umowy o pracę. */
    public static LocalDate higherLeaveFrom(StaffFile file, LocalDate today) {
        int missing = HIGHER_LEAVE_YEARS * 360 - forLeave(file, today).totalDays();
        if (missing <= 0) {
            return today;
        }
        boolean employed = file.employment().stream().anyMatch(contract -> contract.activeOn(today));
        if (!employed) {
            return null;
        }
        Length left = Length.ofDays(missing);
        return today.plusYears(left.years()).plusMonths(left.months()).plusDays(left.days());
    }

    private static List<Span> spans(StaffFile file, LocalDate on) {
        List<Span> result = new ArrayList<>(contractSpans(file, on));
        for (StaffWorkPeriod period : file.workPeriods()) {
            boolean counted = period.getKind() == WorkPeriodKind.EMPLOYMENT
                    || (period.isDocumented() && !on.isBefore(NEW_PERIODS_FROM));
            if (counted && !period.getStartDate().isAfter(on)) {
                result.add(new Span(period.getStartDate(), min(period.getEndDate(), on)));
            }
        }
        return result;
    }

    private static List<Span> contractSpans(StaffFile file, LocalDate on) {
        List<Span> result = new ArrayList<>();
        for (StaffContract contract : file.employment()) {
            if (!contract.getStartDate().isAfter(on)) {
                LocalDate end = contract.getEndDate() == null ? on : min(contract.getEndDate(), on);
                result.add(new Span(contract.getStartDate(), end));
            }
        }
        return result;
    }

    static List<Span> merge(List<Span> spans) {
        List<Span> sorted = spans.stream().sorted(Comparator.comparing(Span::from)).toList();
        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            Span last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && !span.from().isAfter(last.to().plusDays(1))) {
                merged.set(merged.size() - 1, new Span(last.from(), max(last.to(), span.to())));
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    private static List<Span> intersect(List<Span> spans, Span window) {
        List<Span> result = new ArrayList<>();
        for (Span span : spans) {
            LocalDate from = max(span.from(), window.from());
            LocalDate to = min(span.to(), window.to());
            if (!from.isAfter(to)) {
                result.add(new Span(from, to));
            }
        }
        return result;
    }

    private static int days(List<Span> spans) {
        int total = 0;
        for (Span span : spans) {
            Period length = Period.between(span.from(), span.to().plusDays(1));
            total += length.getYears() * 360 + length.getMonths() * 30 + length.getDays();
        }
        return total;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
