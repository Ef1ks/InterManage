package com.intermanage.backend.staff.entity;

import java.time.LocalDate;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Wykorzystany urlop albo zwolnienie. {@code days}: dni pracy z rozkładu, nie kalendarzowe (art. 154(2) KP). */
@Entity
@Table(name = "staff_leaves")
public class StaffLeave extends StaffRecordEntry {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveKind kind;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private int days;

    protected StaffLeave() {
    }

    public StaffLeave(StaffEmployee employee, AppUser createdBy) {
        super(employee, createdBy);
    }

    public void update(LeaveKind kind, LocalDate startDate, LocalDate endDate, int days) {
        this.kind = kind;
        this.startDate = startDate;
        this.endDate = endDate;
        this.days = days;
    }

    /** Dni z grafiku po jego zmianie ({@link com.intermanage.backend.staff.service.StaffLeaveDays}). */
    public void recount(int days) {
        this.days = days;
    }

    public LeaveKind getKind() {
        return kind;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getDays() {
        return days;
    }
}
