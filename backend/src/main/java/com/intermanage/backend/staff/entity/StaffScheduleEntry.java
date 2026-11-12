package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Zmiana pracownika w danym dniu, w grafiku sklepu, w którym tego dnia pracuje. Koniec przed początkiem to zmiana przez
 * północ. Zmiana godzin to znacznik na wpisie i nowy wpis.
 */
@Entity
@Table(name = "staff_schedule_entries")
public class StaffScheduleEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, updatable = false)
    private StaffSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime start;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime end;

    /** Godziny na umowie zlecenia (osobny wiersz grafiku), a nie na umowie o pracę. */
    @Column(nullable = false, updatable = false)
    private boolean civil;

    /** Stanowisko tego dnia, gdy inne niż stałe pracownika; puste = stałe. */
    @Column(name = "position_id", updatable = false)
    private UUID positionId;

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

    protected StaffScheduleEntry() {
    }

    public StaffScheduleEntry(StaffSchedule schedule, StaffEmployee employee, LocalDate workDate, LocalTime start,
            LocalTime end, AppUser createdBy) {
        this(schedule, employee, workDate, start, end, false, null, createdBy);
    }

    public StaffScheduleEntry(StaffSchedule schedule, StaffEmployee employee, LocalDate workDate, LocalTime start,
            LocalTime end, boolean civil, UUID positionId, AppUser createdBy) {
        this.schedule = schedule;
        this.positionId = positionId;
        this.employee = employee;
        this.workDate = workDate;
        this.start = start;
        this.end = end;
        this.civil = civil;
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

    public UUID getPositionId() {
        return positionId;
    }

    public StaffSchedule getSchedule() {
        return schedule;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public boolean isCivil() {
        return civil;
    }
}
