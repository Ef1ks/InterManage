package com.intermanage.backend.staff.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.StaffCompliance;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffScheduleEntry;
import com.intermanage.backend.staff.repository.StaffLeaveRepository;
import com.intermanage.backend.staff.repository.StaffScheduleEntryRepository;

/**
 * Dni nieobecności według grafiku (art. 154² § 1 KP: urlopu udziela się w dni pracy z rozkładu). Dniem urlopu jest
 * tylko dzień, w którym grafik ma zmianę (w którymkolwiek sklepie firmy, także w weekend). Dzień bez zaplanowanej
 * pracy nie zużywa urlopu ani nie obniża wymiaru, więc urlop wpisany przed ułożeniem grafiku ma 0 dni, dopóki
 * kierownik nie ułoży godzin. Zmiana grafiku przelicza urlopy, których dotyczy.
 */
@Service
public class StaffLeaveDays {

    private final StaffScheduleEntryRepository entries;
    private final StaffLeaveRepository leaves;

    public StaffLeaveDays(StaffScheduleEntryRepository entries, StaffLeaveRepository leaves) {
        this.entries = entries;
        this.leaves = leaves;
    }

    /** Dni nieobecności: L4 w dniach kalendarzowych, pozostałe w dniach pracy z grafiku. */
    @Transactional(readOnly = true)
    public int count(UUID employeeId, LeaveKind kind, LocalDate from, LocalDate to) {
        return kind.calendarDays() ? (int) (to.toEpochDay() - from.toEpochDay() + 1) : count(employeeId, from, to);
    }

    @Transactional(readOnly = true)
    public int count(UUID employeeId, LocalDate from, LocalDate to) {
        return (int) entries.findOfEmployees(List.of(employeeId), from, to).stream()
                .map(StaffScheduleEntry::getWorkDate).distinct().count();
    }

    /**
     * Ile dni tego rodzaju wykorzystano w roku bez wpisu {@code skipId}. Limit z ustawy (np. 4 dni na żądanie) pilnuje
     * zarówno zapis urlopu, jak i ułożenie godzin pod nim.
     */
    public void requireLimit(UUID employeeId, LeaveKind kind, int year, UUID skipId, int days) {
        Integer limit = kind.limitDays();
        if (limit == null) {
            return;
        }
        int used = leaves.findActive(List.of(employeeId)).stream()
                .filter(other -> other.getKind() == kind && other.getStartDate().getYear() == year
                        && !other.getId().equals(skipId))
                .mapToInt(StaffLeave::getDays).sum();
        if (used + days > limit) {
            throw ApiException.conflict(StaffCompliance.label(kind) + ": najwyżej " + limit + " dni w roku ("
                    + StaffCompliance.basis(kind) + "). Wykorzystano już " + used + ", a ten urlop miałby " + days
                    + ".");
        }
    }

    /** Przelicza urlopy pracowników, które obejmują któryś ze zmienionych dni grafiku. */
    @Transactional
    public void recount(Set<UUID> employeeIds, LocalDate from, LocalDate to) {
        if (employeeIds.isEmpty()) {
            return;
        }
        for (StaffLeave leave : leaves.findActive(employeeIds)) {
            if (leave.getEndDate().isBefore(from) || leave.getStartDate().isAfter(to)) {
                continue;
            }
            int days = count(leave.getEmployee().getId(), leave.getKind(), leave.getStartDate(), leave.getEndDate());
            if (days > leave.getDays()) {
                requireLimit(leave.getEmployee().getId(), leave.getKind(), leave.getStartDate().getYear(),
                        leave.getId(), days);
            }
            if (days != leave.getDays()) {
                leave.recount(days);
            }
        }
    }
}
