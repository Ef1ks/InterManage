package com.intermanage.backend.staff.entity;

import java.time.LocalDate;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Umowa pracownika. Etat dotyczy tylko umowy o pracę, przy zleceniu i B2B zostaje 1/1. */
@Entity
@Table(name = "staff_contracts")
public class StaffContract extends StaffRecordEntry {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", length = 20)
    private EmploymentType employmentType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "fte_numerator", nullable = false)
    private int fteNumerator = 1;

    @Column(name = "fte_denominator", nullable = false)
    private int fteDenominator = 1;

    /** Umowa z wyjątku art. 25(1) par. 4 KP (np. zastępstwo, praca sezonowa): nie liczy się do limitu 33 miesięcy. */
    @Column(name = "fixed_term_exempt", nullable = false)
    private boolean fixedTermExempt;

    /** Płatne dni wolne w roku z umowy B2B; puste = umowa ich nie przewiduje. */
    @Column(name = "paid_leave_days")
    private Integer paidLeaveDays;

    protected StaffContract() {
    }

    public StaffContract(StaffEmployee employee, AppUser createdBy) {
        super(employee, createdBy);
    }

    public void update(ContractKind kind, EmploymentType employmentType, LocalDate startDate, LocalDate endDate,
            int fteNumerator, int fteDenominator, boolean fixedTermExempt, Integer paidLeaveDays) {
        this.kind = kind;
        this.employmentType = employmentType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.fteNumerator = fteNumerator;
        this.fteDenominator = fteDenominator;
        this.fixedTermExempt = fixedTermExempt;
        this.paidLeaveDays = kind == ContractKind.B2B ? paidLeaveDays : null;
    }

    public Integer getPaidLeaveDays() {
        return paidLeaveDays;
    }

    public boolean isEmployment() {
        return kind == ContractKind.EMPLOYMENT;
    }

    public boolean activeOn(LocalDate day) {
        return !startDate.isAfter(day) && (endDate == null || !endDate.isBefore(day));
    }

    public ContractKind getKind() {
        return kind;
    }

    public EmploymentType getEmploymentType() {
        return employmentType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getFteNumerator() {
        return fteNumerator;
    }

    public int getFteDenominator() {
        return fteDenominator;
    }

    public boolean isFixedTermExempt() {
        return fixedTermExempt;
    }
}
