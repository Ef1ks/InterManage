package com.intermanage.backend.staff.component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.intermanage.backend.staff.entity.TimeDecisionKind;
import com.intermanage.backend.staff.entity.TimeSegmentKind;

/**
 * Karta czasu pracy jednego dnia: zestawia odbicia z grafikiem. Czysta logika bez bazy (sprawdza ją
 * {@code TimeCardTests}).
 * <ul>
 * <li>Czas w grafiku i odbity to praca ({@link Part#WORK}); przerwa niepłatna działu w tym czasie się nie liczy
 * (art. 141 KP), płatna tak (art. 134).</li>
 * <li>Czas odbity poza grafikiem ({@link Part#OVERTIME}) liczy się dopiero po decyzji kierownika: na polecenie
 * pracodawcy albo na wniosek pracownika tak, odrzucony (własna inicjatywa) nie. Do tej pory czeka.</li>
 * <li>Czas z grafiku bez odbicia ({@link Part#SHORTFALL}: spóźnienie, wcześniejsze wyjście) się nie liczy, chyba że
 * kierownik go usprawiedliwi.</li>
 * <li>Odcinki poza grafikiem i braki do {@code toleranceMinutes} nie czekają na decyzję: nadwyżka się nie liczy, a
 * brak liczy się jak w grafiku.</li>
 * <li>Zrównoważenie dnia (decyzja {@code BALANCE}): nierozstrzygnięte braki i czas poza grafikiem znoszą się do
 * mniejszej z sum, od najwcześniejszych odcinków. Brak liczy się wtedy jak w grafiku, a czas poza grafikiem nie jest
 * nadgodzinami; reszta (np. 10 min dłużej niż spóźnienie) zostaje osobnym odcinkiem do decyzji.</li>
 * </ul>
 */
public final class TimeCard {

    private TimeCard() {
    }

    public enum Part {
        WORK,
        BREAK,
        OVERTIME,
        SHORTFALL
    }

    /** Stan odcinka; {@code counted} mówi, czy wchodzi do czasu pracy. */
    public enum Status {
        OK(true),
        BREAK(false),
        PAID_BREAK(true),
        TOLERATED_OVERTIME(false),
        TOLERATED_SHORTFALL(true),
        PENDING(false),
        EMPLOYER_ORDER(true),
        EMPLOYEE_REQUEST(true),
        REJECTED(false),
        UNEXCUSED(false),
        JUSTIFIED(true),
        OFFSET_SHORTFALL(true),
        OFFSET_OVERTIME(false);

        private final boolean counted;

        Status(boolean counted) {
            this.counted = counted;
        }

        public boolean counted() {
            return counted;
        }
    }

    public record Interval(LocalDateTime start, LocalDateTime end) {

        public Interval {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("Koniec odcinka musi być po początku");
            }
        }

        public int minutes() {
            return (int) Duration.between(start, end).toMinutes();
        }

        Interval intersect(Interval other) {
            LocalDateTime from = start.isAfter(other.start) ? start : other.start;
            LocalDateTime to = end.isBefore(other.end) ? end : other.end;
            return to.isAfter(from) ? new Interval(from, to) : null;
        }

        /** Ta część odcinka, której nie przykrywa żaden z {@code others} (posortowanych). */
        List<Interval> minus(List<Interval> others) {
            List<Interval> result = new ArrayList<>();
            LocalDateTime cursor = start;
            for (Interval other : others) {
                if (!other.end.isAfter(cursor) || !other.start.isBefore(end)) {
                    continue;
                }
                if (other.start.isAfter(cursor)) {
                    result.add(new Interval(cursor, other.start));
                }
                if (other.end.isAfter(cursor)) {
                    cursor = other.end;
                }
            }
            if (end.isAfter(cursor)) {
                result.add(new Interval(cursor, end));
            }
            return result;
        }
    }

    /** Przerwa działu: godziny w ciągu dnia; płatna wlicza się do czasu pracy. */
    public record Break(LocalTime start, LocalTime end, boolean paid) {

        /** Przerwa niepłatna. */
        public Break(LocalTime start, LocalTime end) {
            this(start, end, false);
        }
    }

    public record Decision(TimeSegmentKind kind, LocalDateTime start, LocalDateTime end, TimeDecisionKind decision) {
    }

    public record Segment(Part part, LocalDateTime start, LocalDateTime end, int minutes, Status status) {
    }

    /**
     * {@code planned}: minuty z grafiku bez przerw niepłatnych; {@code worked}: czas pracy do rozliczenia;
     * {@code overtime}: zaakceptowane minuty poza grafikiem; {@code pending}: czekające na decyzję; {@code shortfall}:
     * nieusprawiedliwione braki.
     */
    public record Day(List<Segment> segments, int planned, int worked, int overtime, int pending, int shortfall) {
    }

    public static Day day(LocalDate date, Interval planned, List<Interval> worked, List<Break> departmentBreaks,
            int toleranceMinutes, List<Decision> decisions) {
        return day(date, planned, worked, List.of(), departmentBreaks, toleranceMinutes, decisions);
    }

    /**
     * @param date dzień karty (dla przerw)
     * @param planned zmiana z grafiku albo {@code null} (dzień bez pracy w grafiku, także urlop)
     * @param worked zamknięte odcinki z odbić (wejście-wyjście), rozłączne
     * @param takenBreaks przerwy odbite na kiosku (od wyjścia na przerwę do kolejnego wejścia). Gdy są, zastępują
     *        godziny przerw działu: mieszczą się w długości przerw działu (najpierw płatnych, które liczą się do pracy,
     *        potem niepłatnych), a nadwyżka to brak do decyzji.
     */
    public static Day day(LocalDate date, Interval planned, List<Interval> worked, List<Interval> takenBreaks,
            List<Break> departmentBreaks, int toleranceMinutes, List<Decision> decisions) {
        List<Interval> attended = worked.stream().sorted(Comparator.comparing(Interval::start)).toList();
        List<Interval> unpaidWindows = breaks(date, planned, departmentBreaks, false);
        List<Interval> taken = planned == null ? List.of()
                : takenBreaks.stream().map(planned::intersect).filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparing(Interval::start)).toList();
        // Odbita przerwa zastępuje sztywne godziny przerw działu: czas pracy w tych godzinach liczy się normalnie.
        List<Interval> breaks = taken.isEmpty() ? unpaidWindows : List.of();
        List<Segment> segments = new ArrayList<>();

        for (Interval interval : attended) {
            Interval inside = planned == null ? null : planned.intersect(interval);
            if (inside != null) {
                for (Interval work : inside.minus(breaks)) {
                    segments.add(new Segment(Part.WORK, work.start(), work.end(), work.minutes(), Status.OK));
                }
                for (Interval rest : breaks) {
                    Interval inBreak = rest.intersect(inside);
                    if (inBreak != null) {
                        segments.add(new Segment(Part.BREAK, inBreak.start(), inBreak.end(), inBreak.minutes(),
                                Status.BREAK));
                    }
                }
            }
            List<Interval> outside = planned == null ? List.of(interval) : interval.minus(List.of(planned));
            for (Interval extra : outside) {
                segments.add(decided(Part.OVERTIME, extra, toleranceMinutes, decisions));
            }
        }
        if (planned != null) {
            List<Interval> notMissing = new ArrayList<>(breaks);
            notMissing.addAll(taken);
            notMissing.sort(Comparator.comparing(Interval::start));
            for (Interval missing : planned.minus(attended)) {
                for (Interval gap : missing.minus(notMissing)) {
                    segments.add(decided(Part.SHORTFALL, gap, toleranceMinutes, decisions));
                }
            }
            int paidLeft = minutes(breaks(date, planned, departmentBreaks, true));
            int unpaidLeft = minutes(unpaidWindows);
            for (Interval rest : taken) {
                Interval remaining = rest;
                int paid = Math.min(paidLeft, remaining.minutes());
                if (paid > 0) {
                    LocalDateTime cut = remaining.start().plusMinutes(paid);
                    segments.add(new Segment(Part.BREAK, remaining.start(), cut, paid, Status.PAID_BREAK));
                    paidLeft -= paid;
                    remaining = cut.isBefore(remaining.end()) ? new Interval(cut, remaining.end()) : null;
                }
                int unpaid = remaining == null ? 0 : Math.min(unpaidLeft, remaining.minutes());
                if (unpaid > 0) {
                    LocalDateTime cut = remaining.start().plusMinutes(unpaid);
                    segments.add(new Segment(Part.BREAK, remaining.start(), cut, unpaid, Status.BREAK));
                    unpaidLeft -= unpaid;
                    remaining = cut.isBefore(remaining.end()) ? new Interval(cut, remaining.end()) : null;
                }
                if (remaining != null) {
                    // Przerwa dłuższa niż przerwy działu: reszta jest brakiem jak spóźnienie.
                    segments.add(decided(Part.SHORTFALL, remaining, toleranceMinutes, decisions));
                }
            }
        }
        segments.sort(Comparator.comparing(Segment::start).thenComparing(Segment::part));
        if (decisions.stream().anyMatch(decision -> decision.kind() == TimeSegmentKind.BALANCE)) {
            segments = balanced(segments, decisions);
        }

        int plannedMinutes = planned == null ? 0 : planned.minutes() - minutes(unpaidWindows);
        int counted = 0;
        int overtime = 0;
        int pending = 0;
        int shortfall = 0;
        for (Segment segment : segments) {
            if (segment.status().counted()) {
                counted += segment.minutes();
            }
            if (segment.part() == Part.OVERTIME && segment.status().counted()) {
                overtime += segment.minutes();
            }
            if (segment.status() == Status.PENDING) {
                pending += segment.minutes();
            }
            if (segment.status() == Status.UNEXCUSED) {
                shortfall += segment.minutes();
            }
        }
        return new Day(List.copyOf(segments), plannedMinutes, counted, overtime, pending, shortfall);
    }

    /**
     * Znosi nierozstrzygnięte braki z czasem poza grafikiem do mniejszej z sum. Odcinek na granicy dzieli się na część
     * zrównoważoną i resztę; reszta może mieć własną decyzję (po swoich godzinach).
     */
    private static List<Segment> balanced(List<Segment> segments, List<Decision> decisions) {
        int missing = segments.stream().filter(segment -> segment.status() == Status.UNEXCUSED)
                .mapToInt(Segment::minutes).sum();
        int extra = segments.stream().filter(segment -> segment.status() == Status.PENDING)
                .mapToInt(Segment::minutes).sum();
        int offset = Math.min(missing, extra);
        if (offset == 0) {
            return segments;
        }
        int shortfallLeft = offset;
        int overtimeLeft = offset;
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            boolean shortfall = segment.status() == Status.UNEXCUSED;
            boolean overtime = segment.status() == Status.PENDING;
            int left = shortfall ? shortfallLeft : overtime ? overtimeLeft : 0;
            if (left == 0) {
                result.add(segment);
                continue;
            }
            int used = Math.min(left, segment.minutes());
            LocalDateTime cut = segment.start().plusMinutes(used);
            result.add(new Segment(segment.part(), segment.start(), cut, used,
                    shortfall ? Status.OFFSET_SHORTFALL : Status.OFFSET_OVERTIME));
            if (cut.isBefore(segment.end())) {
                Interval rest = new Interval(cut, segment.end());
                Status status = decisions.stream()
                        .filter(decision -> decision.kind() == (shortfall ? TimeSegmentKind.SHORTFALL
                                : TimeSegmentKind.OVERTIME) && decision.start().equals(rest.start())
                                && decision.end().equals(rest.end()))
                        .map(decision -> Status.valueOf(decision.decision().name()))
                        .findFirst().orElse(segment.status());
                result.add(new Segment(segment.part(), rest.start(), rest.end(), rest.minutes(), status));
            }
            if (shortfall) {
                shortfallLeft -= used;
            } else {
                overtimeLeft -= used;
            }
        }
        return result;
    }

    private static Segment decided(Part part, Interval interval, int toleranceMinutes, List<Decision> decisions) {
        int minutes = interval.minutes();
        if (minutes <= toleranceMinutes) {
            return new Segment(part, interval.start(), interval.end(), minutes,
                    part == Part.OVERTIME ? Status.TOLERATED_OVERTIME : Status.TOLERATED_SHORTFALL);
        }
        TimeSegmentKind kind = part == Part.OVERTIME ? TimeSegmentKind.OVERTIME : TimeSegmentKind.SHORTFALL;
        Status status = decisions.stream()
                .filter(decision -> decision.kind() == kind && decision.start().equals(interval.start())
                        && decision.end().equals(interval.end()))
                .map(decision -> Status.valueOf(decision.decision().name()))
                .findFirst()
                .orElse(part == Part.OVERTIME ? Status.PENDING : Status.UNEXCUSED);
        return new Segment(part, interval.start(), interval.end(), minutes, status);
    }

    private static int minutes(List<Interval> intervals) {
        return intervals.stream().mapToInt(Interval::minutes).sum();
    }

    /** Przerwy płatne albo niepłatne w czasie zmiany (zmiana przez północ bierze przerwy obu dni). */
    private static List<Interval> breaks(LocalDate date, Interval planned, List<Break> departmentBreaks,
            boolean paid) {
        if (planned == null) {
            return List.of();
        }
        List<Interval> result = new ArrayList<>();
        for (LocalDate day : List.of(date, date.plusDays(1))) {
            for (Break rest : departmentBreaks) {
                if (rest.paid() != paid || !rest.end().isAfter(rest.start())) {
                    continue;
                }
                Interval window = new Interval(day.atTime(rest.start()), day.atTime(rest.end()));
                Interval inside = window.intersect(planned);
                if (inside != null) {
                    result.add(inside);
                }
            }
        }
        result.sort(Comparator.comparing(Interval::start));
        return result;
    }
}
