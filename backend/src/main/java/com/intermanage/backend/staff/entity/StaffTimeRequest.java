package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Wniosek pracownika o odcinek karty albo zgłoszenie L4. Stan zmienia kierownik, kadry albo pracownik (wycofanie);
 * każdy krok jest też w {@link StaffTimeRequestEvent}, więc nic nie ginie przy cofnięciu.
 */
@Entity
@Table(name = "staff_time_requests")
public class StaffTimeRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 30)
    private TimeRequestKind kind;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "date_to", updatable = false)
    private LocalDate dateTo;

    @Column(name = "start_at", updatable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", updatable = false)
    private LocalDateTime endAt;

    @Column(name = "reason", updatable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TimeRequestStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_by")
    private AppUser managerBy;

    @Column(name = "manager_at")
    private Instant managerAt;

    @Column(name = "manager_note", length = 500)
    private String managerNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hr_by")
    private AppUser hrBy;

    @Column(name = "hr_at")
    private Instant hrAt;

    @Column(name = "hr_note", length = 500)
    private String hrNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decision_id")
    private StaffTimeDecision decision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_id")
    private StaffLeave leave;

    protected StaffTimeRequest() {
    }

    /** Wniosek o odcinek karty. */
    public StaffTimeRequest(StaffEmployee employee, Store store, TimeRequestKind kind, LocalDateTime startAt,
            LocalDateTime endAt, String reason, AppUser createdBy) {
        this.employee = employee;
        this.store = store;
        this.kind = kind;
        this.workDate = startAt.toLocalDate();
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason;
        this.status = TimeRequestStatus.PENDING;
        this.createdBy = createdBy;
    }

    /** Zgłoszenie L4 na dni od-do. */
    public StaffTimeRequest(StaffEmployee employee, Store store, LocalDate from, LocalDate to, String reason,
            AppUser createdBy) {
        this.employee = employee;
        this.store = store;
        this.kind = TimeRequestKind.SICK_LEAVE;
        this.workDate = from;
        this.dateTo = to;
        this.reason = reason;
        this.status = TimeRequestStatus.PENDING;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Krok kierownika: akceptacja (do kadr albo ostateczna) albo odrzucenie. */
    public void managerStep(TimeRequestStatus next, String note, AppUser by) {
        status = next;
        managerBy = by;
        managerAt = Instant.now();
        managerNote = note;
    }

    /** Krok kadr: ostateczna akceptacja albo odrzucenie. */
    public void hrStep(TimeRequestStatus next, String note, AppUser by) {
        status = next;
        hrBy = by;
        hrAt = Instant.now();
        hrNote = note;
    }

    public void cancel() {
        status = TimeRequestStatus.CANCELLED;
    }

    /**
     * Cofnięcie kroku: stan wraca do {@code previous}, a podpisy kroków późniejszych niż ten stan znikają (zostają w
     * historii). Powrót do czekania na kierownika zdejmuje też krok kadr.
     */
    public void revertTo(TimeRequestStatus previous) {
        hrBy = null;
        hrAt = null;
        hrNote = null;
        if (previous == TimeRequestStatus.PENDING) {
            managerBy = null;
            managerAt = null;
            managerNote = null;
        }
        status = previous;
        decision = null;
        leave = null;
    }

    public void approvedWith(StaffTimeDecision decision, StaffLeave leave) {
        this.decision = decision;
        this.leave = leave;
    }

    public UUID getId() {
        return id;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }

    public Store getStore() {
        return store;
    }

    public TimeRequestKind getKind() {
        return kind;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public String getReason() {
        return reason;
    }

    public TimeRequestStatus getStatus() {
        return status;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getManagerBy() {
        return managerBy;
    }

    public Instant getManagerAt() {
        return managerAt;
    }

    public String getManagerNote() {
        return managerNote;
    }

    public AppUser getHrBy() {
        return hrBy;
    }

    public Instant getHrAt() {
        return hrAt;
    }

    public String getHrNote() {
        return hrNote;
    }

    public StaffTimeDecision getDecision() {
        return decision;
    }

    public StaffLeave getLeave() {
        return leave;
    }
}
