package com.intermanage.backend.staff.entity;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Niewykorzystane dni z lat przed {@code year}: stan w chwili założenia kartoteki. Zmiana to nowy wpis i znacznik. */
@Entity
@Table(name = "staff_leave_openings")
public class StaffLeaveOpening extends StaffRecordEntry {

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int days;

    protected StaffLeaveOpening() {
    }

    public StaffLeaveOpening(StaffEmployee employee, int year, int days, AppUser createdBy) {
        super(employee, createdBy);
        this.year = year;
        this.days = days;
    }

    public int getYear() {
        return year;
    }

    public int getDays() {
        return days;
    }
}
