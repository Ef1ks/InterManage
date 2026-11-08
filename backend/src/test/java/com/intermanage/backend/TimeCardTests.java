package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.staff.component.TimeCard;
import com.intermanage.backend.staff.component.TimeCard.Interval;
import com.intermanage.backend.staff.component.TimeCard.Part;
import com.intermanage.backend.staff.component.TimeCard.Status;
import com.intermanage.backend.staff.entity.TimeDecisionKind;
import com.intermanage.backend.staff.entity.TimeSegmentKind;

/** Karta czasu pracy dnia: czas w grafiku, poza nim i braki. */
class TimeCardTests {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);
    private static final Interval SHIFT = interval("06:00", "14:00");

    @Test
    void earlyArrivalAndLateExitWaitForDecision() {
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(interval("05:55", "14:05")), List.of(), 0, List.of());

        assertThat(day.segments()).extracting(TimeCard.Segment::part, TimeCard.Segment::minutes,
                TimeCard.Segment::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Part.OVERTIME, 5, Status.PENDING),
                        org.assertj.core.groups.Tuple.tuple(Part.WORK, 480, Status.OK),
                        org.assertj.core.groups.Tuple.tuple(Part.OVERTIME, 5, Status.PENDING));
        assertThat(day.worked()).isEqualTo(480);
        assertThat(day.pending()).isEqualTo(10);
        assertThat(day.planned()).isEqualTo(480);
    }

    @Test
    void acceptedOvertimeCountsAndRejectedDoesNot() {
        List<TimeCard.Decision> decisions = List.of(
                new TimeCard.Decision(TimeSegmentKind.OVERTIME, at("05:55"), at("06:00"),
                        TimeDecisionKind.EMPLOYEE_REQUEST),
                new TimeCard.Decision(TimeSegmentKind.OVERTIME, at("14:00"), at("14:05"), TimeDecisionKind.REJECTED));
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(interval("05:55", "14:05")), List.of(), 0, decisions);

        assertThat(day.worked()).isEqualTo(485);
        assertThat(day.overtime()).isEqualTo(5);
        assertThat(day.pending()).isZero();
    }

    @Test
    void lateArrivalIsShortfallUntilJustified() {
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(interval("06:10", "13:50")), List.of(), 0, List.of());
        assertThat(day.worked()).isEqualTo(460);
        assertThat(day.shortfall()).isEqualTo(20);

        TimeCard.Day justified = TimeCard.day(DAY, SHIFT, List.of(interval("06:10", "13:50")), List.of(), 0,
                List.of(new TimeCard.Decision(TimeSegmentKind.SHORTFALL, at("06:00"), at("06:10"),
                        TimeDecisionKind.JUSTIFIED)));
        assertThat(justified.worked()).isEqualTo(470);
        assertThat(justified.shortfall()).isEqualTo(10);
    }

    @Test
    void toleranceSkipsDecisionAndKeepsSchedule() {
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(interval("05:55", "13:57")), List.of(), 5, List.of());

        assertThat(day.pending()).isZero();
        assertThat(day.shortfall()).isZero();
        assertThat(day.worked()).isEqualTo(480);
        assertThat(day.segments()).extracting(TimeCard.Segment::status)
                .containsExactly(Status.TOLERATED_OVERTIME, Status.OK, Status.TOLERATED_SHORTFALL);
    }

    @Test
    void unpaidBreakIsNotWorkTimeAndNotShortfall() {
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(interval("06:00", "14:00")),
                List.of(new TimeCard.Break(LocalTime.of(10, 0), LocalTime.of(10, 30))), 0, List.of());
        assertThat(day.planned()).isEqualTo(450);
        assertThat(day.worked()).isEqualTo(450);

        TimeCard.Day out = TimeCard.day(DAY, SHIFT, List.of(interval("06:00", "10:00"), interval("10:30", "14:00")),
                List.of(new TimeCard.Break(LocalTime.of(10, 0), LocalTime.of(10, 30))), 0, List.of());
        assertThat(out.shortfall()).isZero();
        assertThat(out.worked()).isEqualTo(450);
    }

    @Test
    void lateArrivalBalancedByLateExit() {
        List<TimeCard.Decision> balance = List.of(new TimeCard.Decision(TimeSegmentKind.BALANCE,
                DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), TimeDecisionKind.OFFSET));
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(interval("07:00", "15:00")), List.of(), 0, balance);
        assertThat(day.worked()).isEqualTo(480);
        assertThat(day.shortfall()).isZero();
        assertThat(day.pending()).isZero();
        assertThat(day.overtime()).isZero();

        // Zostaje 10 minut dłużej, niż się spóźnił: ta reszta czeka na osobną decyzję.
        TimeCard.Day longer = TimeCard.day(DAY, SHIFT, List.of(interval("07:00", "15:10")), List.of(), 0, balance);
        assertThat(longer.worked()).isEqualTo(480);
        assertThat(longer.pending()).isEqualTo(10);
        assertThat(longer.segments()).filteredOn(segment -> segment.status() == Status.PENDING)
                .extracting(TimeCard.Segment::start).containsExactly(at("15:00"));
    }

    @Test
    void workOnDayWithoutScheduleIsOvertime() {
        TimeCard.Day day = TimeCard.day(DAY, null, List.of(interval("08:00", "12:00")), List.of(), 0, List.of());
        assertThat(day.planned()).isZero();
        assertThat(day.worked()).isZero();
        assertThat(day.pending()).isEqualTo(240);
    }

    @Test
    void absentEmployeeHasWholeShiftMissing() {
        TimeCard.Day day = TimeCard.day(DAY, SHIFT, List.of(), List.of(), 0, List.of());
        assertThat(day.shortfall()).isEqualTo(480);
        assertThat(day.worked()).isZero();
    }

    private static Interval interval(String start, String end) {
        return new Interval(at(start), at(end));
    }

    private static LocalDateTime at(String time) {
        return DAY.atTime(LocalTime.parse(time));
    }
}
