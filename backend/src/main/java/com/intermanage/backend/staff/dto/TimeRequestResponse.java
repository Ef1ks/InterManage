package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.staff.entity.StaffTimeRequestEvent;
import com.intermanage.backend.staff.entity.TimeRequestKind;
import com.intermanage.backend.staff.entity.TimeRequestStatus;

/**
 * Wniosek pracownika z historią. {@code start}/{@code end} przy odcinku karty, {@code workDate}..{@code dateTo} przy
 * L4. {@code byEmployee}: złożył go sam pracownik (w aplikacji). {@code needsHr}: po kierowniku zatwierdzają kadry.
 * {@code canApprove} itd. mówią, co może zrobić osoba, która pyta.
 */
public record TimeRequestResponse(UUID id, UUID employeeId, String employeeName, Integer rcpNumber,
        UUID departmentId, String departmentName, String positionName, TimeRequestKind kind, LocalDate workDate,
        LocalDate dateTo, LocalDateTime start, LocalDateTime end, Integer minutes, String reason,
        TimeRequestStatus status, boolean needsHr, Instant createdAt, String createdBy, boolean byEmployee,
        Step manager, Step hr, boolean canApprove, boolean canReject, boolean canUndo, boolean canCancel,
        List<Event> history) {

    public record Step(String by, Instant at, String note) {
    }

    public record Event(StaffTimeRequestEvent.Action action, Instant at, String by, String note) {
    }
}
