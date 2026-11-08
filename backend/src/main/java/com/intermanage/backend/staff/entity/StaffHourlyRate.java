package com.intermanage.backend.staff.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** Stawka za godzinę pracownika od miesiąca, osobno na umowie o pracę i na zleceniu. Zmiana to znacznik i nowy wpis. */
@Entity
@Table(name = "staff_hourly_rates")
public class StaffHourlyRate {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @Column(nullable = false, updatable = false)
    private boolean civil;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal rate;

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

    protected StaffHourlyRate() {
    }

    public StaffHourlyRate(StaffEmployee employee, boolean civil, LocalDate validFrom, BigDecimal rate,
            AppUser createdBy) {
        this.employee = employee;
        this.civil = civil;
        this.validFrom = validFrom;
        this.rate = rate;
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

    public StaffEmployee getEmployee() {
        return employee;
    }

    public boolean isCivil() {
        return civil;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public BigDecimal getRate() {
        return rate;
    }
}
