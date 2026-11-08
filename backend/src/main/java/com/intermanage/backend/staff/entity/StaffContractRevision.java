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

/** Wersja umowy sprzed poprawki: co było wpisane, kto i kiedy to zmienił. */
@Entity
@Table(name = "staff_contract_revisions")
public class StaffContractRevision {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false, updatable = false)
    private StaffContract contract;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ContractKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", length = 20, updatable = false)
    private EmploymentType employmentType;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Column(name = "fte_numerator", nullable = false, updatable = false)
    private int fteNumerator;

    @Column(name = "fte_denominator", nullable = false, updatable = false)
    private int fteDenominator;

    @Column(name = "fixed_term_exempt", nullable = false, updatable = false)
    private boolean fixedTermExempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", updatable = false)
    private AppUser changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected StaffContractRevision() {
    }

    public StaffContractRevision(StaffContract contract, AppUser changedBy) {
        this.contract = contract;
        this.kind = contract.getKind();
        this.employmentType = contract.getEmploymentType();
        this.startDate = contract.getStartDate();
        this.endDate = contract.getEndDate();
        this.fteNumerator = contract.getFteNumerator();
        this.fteDenominator = contract.getFteDenominator();
        this.fixedTermExempt = contract.isFixedTermExempt();
        this.changedBy = changedBy;
    }

    @PrePersist
    void onCreate() {
        changedAt = Instant.now();
    }
}
