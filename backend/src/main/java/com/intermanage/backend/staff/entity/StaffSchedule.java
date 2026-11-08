package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Grafik jednego działu w jednym sklepie na miesiąc. Powstaje przy pierwszym wpisie albo publikacji. */
@Entity
@Table(name = "staff_schedules")
public class StaffSchedule {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false, updatable = false)
    private StaffDepartment department;

    /** Pierwszy dzień miesiąca. */
    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffSchedule() {
    }

    public StaffSchedule(Store store, StaffDepartment department, LocalDate month, AppUser createdBy) {
        this.store = store;
        this.department = department;
        this.month = month;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public StaffDepartment getDepartment() {
        return department;
    }

    public LocalDate getMonth() {
        return month;
    }
}
