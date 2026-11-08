package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDate;
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

/** Czas wolny za nadgodziny ułożony w grafiku (art. 151² KP). Zmiana i usunięcie to znacznik. */
@Entity
@Table(name = "staff_overtime_time_off")
public class StaffOvertimeTimeOff {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(nullable = false, updatable = false)
    private int minutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TimeOffInitiative initiative;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StaffOvertimeTimeOff() {
    }

    public StaffOvertimeTimeOff(StaffEmployee employee, Store store, LocalDate workDate, int minutes,
            TimeOffInitiative initiative, AppUser createdBy) {
        this.employee = employee;
        this.store = store;
        this.workDate = workDate;
        this.minutes = minutes;
        this.initiative = initiative;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
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

    public LocalDate getWorkDate() {
        return workDate;
    }

    public int getMinutes() {
        return minutes;
    }

    public TimeOffInitiative getInitiative() {
        return initiative;
    }
}
