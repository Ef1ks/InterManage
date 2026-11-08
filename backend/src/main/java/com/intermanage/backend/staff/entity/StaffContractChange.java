package com.intermanage.backend.staff.entity;

import java.time.LocalDate;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Aneks: etat obowiązujący od {@code validFrom}. Ta sama umowa, więc nie liczy się do limitu z art. 25(1) KP. */
@Entity
@Table(name = "staff_contract_changes")
public class StaffContractChange extends StaffRecordEntry {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false, updatable = false)
    private StaffContract contract;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "fte_numerator", nullable = false)
    private int fteNumerator;

    @Column(name = "fte_denominator", nullable = false)
    private int fteDenominator;

    protected StaffContractChange() {
    }

    public StaffContractChange(StaffEmployee employee, StaffContract contract, LocalDate validFrom, int fteNumerator,
            int fteDenominator, AppUser createdBy) {
        super(employee, createdBy);
        this.contract = contract;
        this.validFrom = validFrom;
        this.fteNumerator = fteNumerator;
        this.fteDenominator = fteDenominator;
    }

    public StaffContract getContract() {
        return contract;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public int getFteNumerator() {
        return fteNumerator;
    }

    public int getFteDenominator() {
        return fteDenominator;
    }
}
