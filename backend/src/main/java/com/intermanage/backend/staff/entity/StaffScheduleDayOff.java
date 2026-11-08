package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

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

/** Oznaczony dzień wolny. Przy WN {@code forDate} mówi, za którą niedzielę albo święto. Usunięcie to znacznik. */
@Entity
@Table(name = "staff_schedule_days_off")
public class StaffScheduleDayOff {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, updatable = false)
    private StaffSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @Column(name = "day_off_date", nullable = false, updatable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5, updatable = false)
    private ScheduleDayOffKind kind;

    @Column(name = "for_date", updatable = false)
    private LocalDate forDate;

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

    protected StaffScheduleDayOff() {
    }

    public StaffScheduleDayOff(StaffSchedule schedule, StaffEmployee employee, LocalDate day, ScheduleDayOffKind kind,
            LocalDate forDate, AppUser createdBy) {
        this.schedule = schedule;
        this.employee = employee;
        this.day = day;
        this.kind = kind;
        this.forDate = forDate;
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

    public StaffSchedule getSchedule() {
        return schedule;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }

    public LocalDate getDay() {
        return day;
    }

    public ScheduleDayOffKind getKind() {
        return kind;
    }

    public LocalDate getForDate() {
        return forDate;
    }
}
