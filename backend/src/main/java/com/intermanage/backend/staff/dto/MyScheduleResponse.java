package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.ScheduleDayOffKind;

/**
 * Własny grafik zalogowanego pracownika, każdy dzień okresu po kolei: zmiany z ostatnich publikacji grafików we
 * wszystkich sklepach (wersja robocza nie jest pokazywana, bo nie została jeszcze przekazana), dni wolne W5 / WN
 * i własny urlop z Kadr. {@code employee = false}: konto nie jest pracownikiem żadnego sklepu.
 */
public record MyScheduleResponse(boolean employee, LocalDate from, LocalDate to, List<Day> days) {

    public record Day(LocalDate date, List<Shift> shifts, DayOff dayOff, LeaveKind leave) {
    }

    public record Shift(LocalTime start, LocalTime end, String storeNumber, String storeName, String department) {
    }

    public record DayOff(ScheduleDayOffKind kind, LocalDate forDate) {
    }
}
