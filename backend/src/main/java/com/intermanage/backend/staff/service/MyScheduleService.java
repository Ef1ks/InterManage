package com.intermanage.backend.staff.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.staff.dto.MyScheduleResponse;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.mapper.MyScheduleMapper;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffLeaveRepository;
import com.intermanage.backend.staff.repository.StaffScheduleVersionDayOffRepository;
import com.intermanage.backend.staff.repository.StaffScheduleVersionEntryRepository;

/**
 * „Mój grafik”: każdy widzi tylko swoje zmiany. Bierzemy ostatnią publikację każdego grafiku (to, co pracownikom
 * przekazano), z wszystkich sklepów i działów, w których osoba ma wpisy, także sprzed przeniesienia.
 */
@Service
public class MyScheduleService {

    /** Najdłuższy okres jednego zapytania, jak okres kart czasu pracy. */
    static final int MAX_DAYS = 62;

    private final StaffEmployeeRepository employees;
    private final StaffScheduleVersionEntryRepository entries;
    private final StaffScheduleVersionDayOffRepository daysOff;
    private final StaffLeaveRepository leaves;
    private final MyScheduleMapper mapper;

    public MyScheduleService(StaffEmployeeRepository employees, StaffScheduleVersionEntryRepository entries,
            StaffScheduleVersionDayOffRepository daysOff, StaffLeaveRepository leaves, MyScheduleMapper mapper) {
        this.employees = employees;
        this.entries = entries;
        this.daysOff = daysOff;
        this.leaves = leaves;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public MyScheduleResponse of(UUID userId, LocalDate from, LocalDate to) {
        ReportPeriod.check(from, to, MAX_DAYS, "Okres grafiku");
        List<UUID> ids = employees.findIdsOfUser(userId);
        if (ids.isEmpty()) return new MyScheduleResponse(false, from, to, List.of());

        Map<LocalDate, List<MyScheduleResponse.Shift>> shifts = new HashMap<>();
        entries.findLatestPublished(ids, from, to).forEach(entry -> shifts
                .computeIfAbsent(entry.getWorkDate(), day -> new ArrayList<>())
                .add(mapper.toShift(entry)));
        shifts.values().forEach(list -> list.sort(Comparator.comparing(MyScheduleResponse.Shift::start)));

        Map<LocalDate, MyScheduleResponse.DayOff> off = new HashMap<>();
        daysOff.findLatestPublished(ids, from, to).forEach(day -> off.put(day.getDay(), mapper.toDayOff(day)));

        List<StaffLeave> own = leaves.findActive(ids).stream()
                .filter(leave -> !leave.getEndDate().isBefore(from) && !leave.getStartDate().isAfter(to))
                .toList();

        List<MyScheduleResponse.Day> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(new MyScheduleResponse.Day(day, shifts.getOrDefault(day, List.of()), off.get(day),
                    leaveOn(own, day)));
        }
        return new MyScheduleResponse(true, from, to, days);
    }

    private static LeaveKind leaveOn(List<StaffLeave> leaves, LocalDate day) {
        return leaves.stream()
                .filter(leave -> !day.isBefore(leave.getStartDate()) && !day.isAfter(leave.getEndDate()))
                .map(StaffLeave::getKind)
                .findFirst()
                .orElse(null);
    }
}
