package com.intermanage.backend.staff.entity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Zmiana w opublikowanej wersji grafiku: kopia wartości, bo późniejsze poprawki nie mogą jej dotknąć. */
@Entity
@Immutable
@Table(name = "staff_schedule_version_entries")
public class StaffScheduleVersionEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private StaffScheduleVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime start;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime end;

    @Column(nullable = false, updatable = false)
    private boolean civil;

    protected StaffScheduleVersionEntry() {
    }

    public StaffScheduleVersionEntry(StaffScheduleVersion version, StaffScheduleEntry entry) {
        this.version = version;
        this.employee = entry.getEmployee();
        this.workDate = entry.getWorkDate();
        this.start = entry.getStart();
        this.end = entry.getEnd();
        this.civil = entry.isCivil();
    }

    public boolean isCivil() {
        return civil;
    }

    public StaffScheduleVersion getVersion() {
        return version;
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
}
