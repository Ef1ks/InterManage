package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

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

/** Odbicie karty: kolejne odbicia pracownika to na zmianę wejście i wyjście. Usunięcie to znacznik. */
@Entity
@Table(name = "staff_time_punches")
public class StaffTimePunch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "punched_at", nullable = false, updatable = false)
    private Instant punchedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TimePunchSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reader_id", updatable = false)
    private StaffTimeReader reader;

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

    protected StaffTimePunch() {
    }

    public StaffTimePunch(StaffEmployee employee, Store store, Instant punchedAt, TimePunchSource source, StaffTimeReader reader, AppUser createdBy) {
        this.employee = employee;
        this.store = store;
        this.punchedAt = punchedAt;
        this.source = source;
        this.reader = reader;
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

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }

    public Store getStore() {
        return store;
    }

    public Instant getPunchedAt() {
        return punchedAt;
    }

    public TimePunchSource getSource() {
        return source;
    }
}
