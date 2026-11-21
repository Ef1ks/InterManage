package com.intermanage.backend.staff.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.TimeRequestPdf;
import com.intermanage.backend.staff.dto.LeaveRequest;
import com.intermanage.backend.staff.dto.TimeCardResponse;
import com.intermanage.backend.staff.dto.TimeRequestInput;
import com.intermanage.backend.staff.dto.TimeRequestResponse;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffTimeDecision;
import com.intermanage.backend.staff.entity.StaffTimeRequest;
import com.intermanage.backend.staff.entity.StaffTimeRequestEvent;
import com.intermanage.backend.staff.entity.StaffTimeRequestEvent.Action;
import com.intermanage.backend.staff.entity.TimeRequestApprover;
import com.intermanage.backend.staff.entity.TimeRequestKind;
import com.intermanage.backend.staff.entity.TimeRequestStatus;
import com.intermanage.backend.staff.entity.TimeSegmentKind;
import com.intermanage.backend.push.service.PushService;
import com.intermanage.backend.staff.repository.StaffDepartmentManagerRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffLeaveRepository;
import com.intermanage.backend.staff.repository.StaffTimeRequestEventRepository;
import com.intermanage.backend.staff.repository.StaffTimeRequestRepository;

/**
 * Wnioski pracowników o odcinki karty (nadgodziny z własnej inicjatywy, wyjście prywatne, spóźnienie z przyczyną…) i
 * zgłoszenia L4. Pracownik składa wniosek na odcinek, który widzi w swojej karcie; kierownik działu akceptuje albo
 * odrzuca; gdy sklep wybrał kadry (zawsze przy L4), zaakceptowany czeka na kadry ({@code STAFF_RECORDS:EDIT}).
 * Ostateczna akceptacja zapisuje decyzję w karcie albo zwolnienie w Kadrach, cofnięcie ją zdejmuje.
 */
@Service
public class StaffTimeRequestService {

    /** Najdłuższe zgłoszenie L4 (182 dni to najdłuższy okres zasiłkowy bez gruźlicy i ciąży). */
    private static final int MAX_SICK_DAYS = 182;
    private static final String HR_EDIT = "STAFF_RECORDS:EDIT";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.MM.yyyy");

    private final StaffTimeRequestRepository requests;
    private final StaffTimeRequestEventRepository events;
    private final StaffEmployeeRepository employees;
    private final StaffLeaveRepository leaves;
    private final StaffTimeService time;
    private final StaffTimeRulesService rules;
    private final StaffRecordService records;
    private final AppUserRepository users;
    private final TimeRequestPdf pdf;
    private final StaffDepartmentManagerRepository managers;
    private final PushService push;

    public StaffTimeRequestService(StaffTimeRequestRepository requests, StaffTimeRequestEventRepository events,
            StaffEmployeeRepository employees, StaffLeaveRepository leaves, StaffTimeService time,
            StaffTimeRulesService rules, StaffRecordService records, AppUserRepository users, TimeRequestPdf pdf,
            StaffDepartmentManagerRepository managers, PushService push) {
        this.managers = managers;
        this.push = push;
        this.requests = requests;
        this.events = events;
        this.employees = employees;
        this.leaves = leaves;
        this.time = time;
        this.rules = rules;
        this.records = records;
        this.users = users;
        this.pdf = pdf;
    }

    // ---------------------------------------------------------------- pracownik

    /** Wnioski zalogowanego pracownika (ze wszystkich jego wpisów pracownika). */
    @Transactional(readOnly = true)
    public List<TimeRequestResponse> own(UUID userId) {
        List<UUID> ids = employees.findIdsOfUser(userId);
        return ids.isEmpty() ? List.of() : responses(requests.findOfEmployees(ids), null);
    }

    @Transactional
    public TimeRequestResponse submitOwn(UUID userId, TimeRequestInput.Create input) {
        StaffEmployee employee = employees.findActiveOfUsers(List.of(userId)).stream().findFirst()
                .orElseThrow(() -> ApiException.forbidden("To konto nie jest pracownikiem"));
        StaffTimeRequest saved = create(employee, input, users.getReferenceById(userId));
        return responses(List.of(saved), null).getFirst();
    }

    /** Pracownik wycofuje swój wniosek, dopóki nie jest rozstrzygnięty. */
    @Transactional
    public TimeRequestResponse cancelOwn(UUID userId, UUID requestId) {
        StaffTimeRequest request = requests.findById(requestId)
                .filter(found -> found.getEmployee().getUser() != null
                        && found.getEmployee().getUser().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego wniosku"));
        if (!request.getStatus().open()) {
            throw ApiException.conflict("Wniosek jest już rozstrzygnięty");
        }
        TimeRequestStatus from = request.getStatus();
        request.cancel();
        events.save(new StaffTimeRequestEvent(request, Action.CANCELLED, from, TimeRequestStatus.CANCELLED, null,
                users.getReferenceById(userId)));
        return responses(List.of(request), null).getFirst();
    }

    private StaffTimeRequest create(StaffEmployee employee, TimeRequestInput.Create input, AppUser actor) {
        TimeRequestKind kind = input.kind();
        String reason = input.reason() == null || input.reason().isBlank() ? null : input.reason().strip();
        if (kind.reasonRequired() && reason == null) {
            throw ApiException.badRequest("Podaj przyczynę");
        }
        StaffTimeRequest request;
        if (kind == TimeRequestKind.SICK_LEAVE) {
            if (input.from() == null || input.to() == null) {
                throw ApiException.badRequest("Podaj dni zwolnienia");
            }
            if (input.to().isBefore(input.from())) {
                throw ApiException.badRequest("Koniec zwolnienia nie może być przed początkiem");
            }
            if (ChronoUnit.DAYS.between(input.from(), input.to()) >= MAX_SICK_DAYS) {
                throw ApiException.badRequest("Zwolnienie zgłasza się najwyżej na " + MAX_SICK_DAYS + " dni");
            }
            boolean overlaps = requests.findOpen(employee.getId(), input.from().minusDays(MAX_SICK_DAYS), input.to())
                    .stream().anyMatch(open -> open.getKind() == TimeRequestKind.SICK_LEAVE
                            && !open.getDateTo().isBefore(input.from()));
            if (overlaps) {
                throw ApiException.conflict("Te dni ma już zgłoszenie L4, które czeka na decyzję");
            }
            request = new StaffTimeRequest(employee, employee.getStore(), input.from(), input.to(), reason, actor);
        } else {
            if (input.start() == null || input.end() == null || !input.end().isAfter(input.start())) {
                throw ApiException.badRequest("Wskaż odcinek z karty czasu pracy");
            }
            TimeCardResponse.Segment segment = openSegment(employee, kind.segment(), input.start(), input.end());
            if (segment == null) {
                throw ApiException.conflict("Tego odcinka nie ma w karcie albo ma już decyzję. Odśwież kartę.");
            }
            LocalDate day = input.start().toLocalDate();
            boolean taken = requests.findOpen(employee.getId(), day, day).stream()
                    .anyMatch(open -> input.start().equals(open.getStartAt()) && input.end().equals(open.getEndAt()));
            if (taken) {
                throw ApiException.conflict("Ten odcinek ma już wniosek, który czeka na decyzję");
            }
            request = new StaffTimeRequest(employee, employee.getStore(), kind, input.start(), input.end(), reason,
                    actor);
        }
        StaffTimeRequest saved = requests.saveAndFlush(request);
        events.save(new StaffTimeRequestEvent(saved, Action.SUBMITTED, null, TimeRequestStatus.PENDING, reason,
                actor));
        List<UUID> managerIds = managers.findActive(employee.getPosition().getDepartment().getId()).stream()
                .map(manager -> manager.getUser().getId()).filter(id -> !id.equals(actor.getId())).toList();
        push.notify(managerIds, "Nowy wniosek: " + employee.getDisplayName(),
                kind.label() + ", " + saved.getWorkDate().format(DAY) + ".", Map.of("url", "/"));
        return saved;
    }

    /**
     * Odcinek karty czekający na decyzję: czas poza grafikiem bez decyzji albo nieusprawiedliwiony brak, dokładnie w
     * tych godzinach. Bez karty albo już rozstrzygnięty: {@code null}.
     */
    private TimeCardResponse.Segment openSegment(StaffEmployee employee, TimeSegmentKind kind,
            java.time.LocalDateTime start, java.time.LocalDateTime end) {
        String waiting = kind == TimeSegmentKind.OVERTIME ? "PENDING" : "UNEXCUSED";
        return time.dayCard(employee, start.toLocalDate()).stream()
                .flatMap(card -> card.segments().stream())
                .filter(segment -> segment.part().equals(kind.name()) && segment.status().equals(waiting)
                        && segment.start().equals(start) && segment.end().equals(end))
                .findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- kierownik i kadry

    /** Wnioski sklepu: otwarte (do decyzji) albo z dni okresu; każdy widzi wnioski działów, o których decyduje. */
    @Transactional(readOnly = true)
    public List<TimeRequestResponse> list(UUID storeId, boolean open, LocalDate from, LocalDate to,
            AppUserPrincipal principal) {
        List<StaffTimeRequest> found = open
                ? requests.findInStore(storeId, Set.of(TimeRequestStatus.PENDING, TimeRequestStatus.MANAGER_APPROVED))
                : requests.findInStore(storeId, from, to);
        boolean hr = hr(principal);
        return responses(found.stream()
                .filter(request -> hr || manages(request, principal))
                .toList(), principal);
    }

    /** Ile wniosków czeka na tę osobę: jako kierownika i jako kadry. */
    @Transactional(readOnly = true)
    public Map<String, Integer> count(UUID storeId, AppUserPrincipal principal) {
        List<StaffTimeRequest> open = requests.findInStore(storeId,
                Set.of(TimeRequestStatus.PENDING, TimeRequestStatus.MANAGER_APPROVED));
        int manager = (int) open.stream()
                .filter(request -> request.getStatus() == TimeRequestStatus.PENDING && manages(request, principal))
                .count();
        int hr = hr(principal) ? (int) open.stream()
                .filter(request -> request.getStatus() == TimeRequestStatus.MANAGER_APPROVED).count() : 0;
        return Map.of("manager", manager, "hr", hr, "total", manager + hr);
    }

    @Transactional
    public TimeRequestResponse approve(UUID storeId, UUID requestId, TimeRequestInput.Step step,
            AppUserPrincipal principal) {
        StaffTimeRequest request = inStore(storeId, requestId);
        AppUser actor = users.getReferenceById(principal.getId());
        String note = note(step);
        TimeRequestStatus from = request.getStatus();
        if (from == TimeRequestStatus.PENDING) {
            requireManager(request, principal);
            // Osoba, która jest też kadrami (np. właściciel), zatwierdza od razu ostatecznie, jednym krokiem.
            boolean finalNow = !needsHr(request) || hr(principal);
            if (finalNow) {
                finish(request, note, actor, principal);
                request.managerStep(TimeRequestStatus.APPROVED, note, actor);
                if (needsHr(request)) {
                    request.hrStep(TimeRequestStatus.APPROVED, note, actor);
                }
            } else {
                request.managerStep(TimeRequestStatus.MANAGER_APPROVED, note, actor);
            }
            events.save(new StaffTimeRequestEvent(request, Action.MANAGER_APPROVED, from, request.getStatus(), note,
                    actor));
        } else if (from == TimeRequestStatus.MANAGER_APPROVED) {
            requireHr(principal);
            finish(request, note, actor, principal);
            request.hrStep(TimeRequestStatus.APPROVED, note, actor);
            events.save(new StaffTimeRequestEvent(request, Action.HR_APPROVED, from, TimeRequestStatus.APPROVED,
                    note, actor));
        } else {
            throw ApiException.conflict("Wniosek jest już rozstrzygnięty");
        }
        if (request.getStatus() == TimeRequestStatus.APPROVED) {
            tellEmployee(request, "Wniosek zatwierdzony", note);
        }
        return responses(List.of(request), principal).getFirst();
    }

    @Transactional
    public TimeRequestResponse reject(UUID storeId, UUID requestId, TimeRequestInput.Step step,
            AppUserPrincipal principal) {
        StaffTimeRequest request = inStore(storeId, requestId);
        AppUser actor = users.getReferenceById(principal.getId());
        String note = note(step);
        TimeRequestStatus from = request.getStatus();
        if (from == TimeRequestStatus.PENDING) {
            requireManager(request, principal);
            request.managerStep(TimeRequestStatus.REJECTED, note, actor);
            events.save(new StaffTimeRequestEvent(request, Action.MANAGER_REJECTED, from, TimeRequestStatus.REJECTED,
                    note, actor));
        } else if (from == TimeRequestStatus.MANAGER_APPROVED) {
            requireHr(principal);
            request.hrStep(TimeRequestStatus.REJECTED, note, actor);
            events.save(new StaffTimeRequestEvent(request, Action.HR_REJECTED, from, TimeRequestStatus.REJECTED, note,
                    actor));
        } else {
            throw ApiException.conflict("Wniosek jest już rozstrzygnięty");
        }
        tellEmployee(request, "Wniosek odrzucony", note);
        return responses(List.of(request), principal).getFirst();
    }

    /** Cofa ostatni krok kierownika albo kadr: decyzja w karcie albo L4 w Kadrach dostaje znacznik. */
    @Transactional
    public TimeRequestResponse undo(UUID storeId, UUID requestId, AppUserPrincipal principal) {
        StaffTimeRequest request = inStore(storeId, requestId);
        StaffTimeRequestEvent last = lastStep(request);
        if (last == null) {
            throw ApiException.conflict("Nie ma czego cofnąć");
        }
        boolean hrStep = last.getAction() == Action.HR_APPROVED || last.getAction() == Action.HR_REJECTED;
        if (hrStep) {
            requireHr(principal);
        } else {
            requireManager(request, principal);
        }
        AppUser actor = users.getReferenceById(principal.getId());
        StaffTimeDecision decision = request.getDecision();
        if (decision != null && decision.getDeletedAt() == null) {
            decision.markDeleted(actor);
        }
        StaffLeave leave = request.getLeave();
        if (leave != null && leave.getDeletedAt() == null) {
            records.removeLeave(request.getStore().getId(), request.getEmployee().getId(), leave.getId(),
                    principal.getId());
        }
        TimeRequestStatus from = request.getStatus();
        request.revertTo(last.getFromStatus());
        events.save(new StaffTimeRequestEvent(request, Action.UNDONE, from, request.getStatus(), null, actor));
        return responses(List.of(request), principal).getFirst();
    }

    /** Wydruk wniosków (każdy na osobnej kartce), w kolejności podanej. */
    @Transactional(readOnly = true)
    public StaffTimeService.PdfFile pdf(UUID storeId, List<UUID> ids, AppUserPrincipal principal) {
        if (ids.isEmpty() || ids.size() > 200) {
            throw ApiException.badRequest("Wybierz od 1 do 200 wniosków");
        }
        boolean hr = hr(principal);
        List<StaffTimeRequest> chosen = ids.stream().map(id -> inStore(storeId, id))
                .filter(request -> hr || manages(request, principal)).toList();
        if (chosen.size() != ids.size()) {
            throw ApiException.forbidden("Wnioski drukuje kierownik działu albo kadry");
        }
        Map<UUID, List<StaffTimeRequestEvent>> history = events.findOf(ids).stream()
                .collect(Collectors.groupingBy(event -> event.getRequest().getId()));
        List<TimeRequestPdf.Request> pages = chosen.stream().map(request -> new TimeRequestPdf.Request(
                request.getKind(), request.getEmployee().getDisplayName(), request.getEmployee().getRcpNumber(),
                request.getEmployee().getPosition().getDepartment().getName(),
                request.getEmployee().getPosition().getName(), request.getWorkDate(), request.getDateTo(),
                request.getStartAt(), request.getEndAt(), request.getReason(), request.getStatus(),
                request.getCreatedAt(), name(request.getCreatedBy()), byEmployee(request),
                step(request.getManagerBy(), request.getManagerAt(), request.getManagerNote()),
                step(request.getHrBy(), request.getHrAt(), request.getHrNote()), needsHr(request),
                history.getOrDefault(request.getId(), List.of()).stream()
                        .map(event -> new TimeRequestPdf.Event(event.getAction(), event.getCreatedAt(),
                                name(event.getCreatedBy()), event.getNote()))
                        .toList()))
                .toList();
        StaffTimeService.PdfFile file = new StaffTimeService.PdfFile(
                chosen.size() == 1 ? "wniosek-" + chosen.getFirst().getWorkDate() + ".pdf" : "wnioski.pdf",
                pdf.pdf(time.employerFor(chosen.getFirst().getStore()), pages));
        return file;
    }

    // ---------------------------------------------------------------- reguły

    /** Powiadomienie pracownika z aplikacją o rozstrzygnięciu jego wniosku. */
    private void tellEmployee(StaffTimeRequest request, String title, String note) {
        if (request.getEmployee().getUser() == null) {
            return;
        }
        String body = request.getKind().label() + ", " + request.getWorkDate().format(DAY) + "."
                + (note == null ? "" : " Uwaga: " + note);
        push.notify(List.of(request.getEmployee().getUser().getId()), title, body, Map.of("url", "/requests"));
    }

    /**
     * Ostatnia akceptacja wpisuje wniosek do czasu pracy: decyzja o odcinku (odcinek musi dalej czekać w karcie) albo
     * zwolnienie lekarskie w Kadrach.
     */
    private void finish(StaffTimeRequest request, String note, AppUser actor, AppUserPrincipal principal) {
        if (request.getKind() == TimeRequestKind.SICK_LEAVE) {
            records.saveLeave(request.getStore().getId(), request.getEmployee().getId(), null,
                    new LeaveRequest(LeaveKind.SICK, request.getWorkDate(), request.getDateTo()), principal.getId());
            StaffLeave leave = leaves.findActive(List.of(request.getEmployee().getId())).stream()
                    .filter(found -> found.getKind() == LeaveKind.SICK
                            && found.getStartDate().equals(request.getWorkDate())
                            && found.getEndDate().equals(request.getDateTo()))
                    .max(Comparator.comparing(StaffLeave::getCreatedAt)).orElseThrow();
            request.approvedWith(null, leave);
            return;
        }
        if (openSegment(request.getEmployee(), request.getKind().segment(), request.getStartAt(),
                request.getEndAt()) == null) {
            throw ApiException.conflict("Odcinka z wniosku nie ma już w karcie albo ma inną decyzję (np. nowe odbicia "
                    + "albo zmieniony grafik). Odrzuć wniosek, pracownik złoży nowy.");
        }
        String text = "Wniosek pracownika" + (request.getReason() == null ? "" : ": " + request.getReason())
                + (note == null ? "" : " · " + note);
        StaffTimeDecision decision = time.record(request.getEmployee(), request.getKind().segment(),
                request.getStartAt(), request.getEndAt(), request.getKind().decision(),
                text.length() > 500 ? text.substring(0, 500) : text, actor);
        request.approvedWith(decision, null);
    }

    /** Po kierowniku zatwierdzają kadry: gdy tak ustawił sklep, a L4 zawsze (wpis w Kadrach). */
    private boolean needsHr(StaffTimeRequest request) {
        return request.getKind() == TimeRequestKind.SICK_LEAVE
                || rules.values(request.getStore().getId()).finalApprover() == TimeRequestApprover.HR;
    }

    private boolean manages(StaffTimeRequest request, AppUserPrincipal principal) {
        return time.decides(request.getEmployee().getPosition().getDepartment(), principal);
    }

    private static boolean hr(AppUserPrincipal principal) {
        return principal.administers() || has(principal, HR_EDIT);
    }

    private void requireManager(StaffTimeRequest request, AppUserPrincipal principal) {
        if (!manages(request, principal)) {
            throw ApiException.forbidden("O wniosku decyduje kierownik działu");
        }
    }

    private static void requireHr(AppUserPrincipal principal) {
        if (!hr(principal)) {
            throw ApiException.forbidden("Ten krok zatwierdzają kadry");
        }
    }

    private static boolean has(AppUserPrincipal principal, String authority) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }

    private StaffTimeRequest inStore(UUID storeId, UUID requestId) {
        return requests.findById(requestId).filter(request -> request.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego wniosku"));
    }

    /** Ostatni krok decyzji (akceptacja albo odrzucenie), o ile po nim nic nie cofnięto ani nie wycofano. */
    private StaffTimeRequestEvent lastStep(StaffTimeRequest request) {
        List<StaffTimeRequestEvent> history = events.findOf(List.of(request.getId()));
        if (history.isEmpty()) {
            return null;
        }
        StaffTimeRequestEvent last = history.getLast();
        return switch (last.getAction()) {
            case MANAGER_APPROVED, MANAGER_REJECTED, HR_APPROVED, HR_REJECTED -> last;
            default -> null;
        };
    }

    private static String note(TimeRequestInput.Step step) {
        return step == null || step.note() == null || step.note().isBlank() ? null : step.note().strip();
    }

    private static boolean byEmployee(StaffTimeRequest request) {
        return request.getCreatedBy() != null && request.getEmployee().getUser() != null
                && request.getCreatedBy().getId().equals(request.getEmployee().getUser().getId());
    }

    private static String name(AppUser user) {
        return user == null ? null : user.getDisplayName();
    }

    private static TimeRequestResponse.Step step(AppUser by, java.time.Instant at, String note) {
        return at == null ? null : new TimeRequestResponse.Step(name(by), at, note);
    }

    /** {@code principal} = {@code null}: widok pracownika (może wycofać otwarty, nic więcej). */
    private List<TimeRequestResponse> responses(List<StaffTimeRequest> found, AppUserPrincipal principal) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<StaffTimeRequestEvent>> history = events.findOf(found.stream()
                .map(StaffTimeRequest::getId).toList()).stream()
                .collect(Collectors.groupingBy(event -> event.getRequest().getId()));
        boolean hr = principal != null && hr(principal);
        return found.stream().map(request -> {
            List<StaffTimeRequestEvent> steps = history.getOrDefault(request.getId(), List.of());
            boolean manager = principal != null && manages(request, principal);
            boolean needsHr = needsHr(request);
            TimeRequestStatus status = request.getStatus();
            boolean canDecide = status == TimeRequestStatus.PENDING && manager
                    || status == TimeRequestStatus.MANAGER_APPROVED && hr;
            StaffTimeRequestEvent last = steps.isEmpty() ? null : steps.getLast();
            boolean canUndo = last != null && switch (last.getAction()) {
                case HR_APPROVED, HR_REJECTED -> hr;
                case MANAGER_APPROVED, MANAGER_REJECTED -> manager;
                default -> false;
            };
            StaffEmployee employee = request.getEmployee();
            return new TimeRequestResponse(request.getId(), employee.getId(), employee.getDisplayName(),
                    employee.getRcpNumber(), employee.getPosition().getDepartment().getId(),
                    employee.getPosition().getDepartment().getName(), employee.getPosition().getName(),
                    request.getKind(), request.getWorkDate(), request.getDateTo(), request.getStartAt(),
                    request.getEndAt(), request.getStartAt() == null ? null
                            : (int) Duration.between(request.getStartAt(), request.getEndAt()).toMinutes(),
                    request.getReason(), status, needsHr, request.getCreatedAt(), name(request.getCreatedBy()),
                    byEmployee(request), step(request.getManagerBy(), request.getManagerAt(), request.getManagerNote()),
                    step(request.getHrBy(), request.getHrAt(), request.getHrNote()), canDecide, canDecide, canUndo,
                    principal == null && status.open(),
                    steps.stream().map(event -> new TimeRequestResponse.Event(event.getAction(), event.getCreatedAt(),
                            name(event.getCreatedBy()), event.getNote())).toList());
        }).toList();
    }
}
