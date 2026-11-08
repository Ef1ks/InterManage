package com.intermanage.backend.staff.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Dzień wolny w opublikowanej wersji grafiku: kopia wartości, baza nie pozwala jej zmienić. */
@Entity
@Immutable
@Table(name = "staff_schedule_version_days_off")
public class StaffScheduleVersionDayOff {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private StaffScheduleVersion version;

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

    protected StaffScheduleVersionDayOff() {
    }

    public StaffScheduleVersionDayOff(StaffScheduleVersion version, StaffScheduleDayOff dayOff) {
        this.version = version;
        this.employee = dayOff.getEmployee();
        this.day = dayOff.getDay();
        this.kind = dayOff.getKind();
        this.forDate = dayOff.getForDate();
    }

    public StaffScheduleVersion getVersion() {
        return version;
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
