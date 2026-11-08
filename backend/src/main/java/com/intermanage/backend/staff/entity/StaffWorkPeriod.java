package com.intermanage.backend.staff.entity;

import java.time.LocalDate;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Okres spoza tej firmy liczony do stażu. Okresy z art. 302(1) KP (zlecenie, działalność, praca za granicą) wliczają
 * się dopiero po udokumentowaniu zaświadczeniem ZUS albo innym dokumentem ({@code documented}).
 */
@Entity
@Table(name = "staff_work_periods")
public class StaffWorkPeriod extends StaffRecordEntry {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkPeriodKind kind;

    @Column(length = 150)
    private String employer;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean documented;

    protected StaffWorkPeriod() {
    }

    public StaffWorkPeriod(StaffEmployee employee, AppUser createdBy) {
        super(employee, createdBy);
    }

    public void update(WorkPeriodKind kind, String employer, LocalDate startDate, LocalDate endDate,
            boolean documented) {
        this.kind = kind;
        this.employer = employer;
        this.startDate = startDate;
        this.endDate = endDate;
        this.documented = documented;
    }

    public WorkPeriodKind getKind() {
        return kind;
    }

    public String getEmployer() {
        return employer;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean isDocumented() {
        return documented;
    }
}
