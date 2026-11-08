package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalTime;
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

/**
 * Przerwa działu w danym sklepie: godziny od-do i czy wlicza się do czasu pracy (art. 134 KP: płatna, art. 141:
 * niepłatna). Grafik jej nie pokazuje, posłuży rejestracji czasu pracy. Zmiana i usunięcie to znacznik.
 */
@Entity
@Table(name = "staff_department_breaks")
public class StaffDepartmentBreak {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false, updatable = false)
    private StaffDepartment department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime start;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime end;

    @Column(name = "paid", nullable = false, updatable = false)
    private boolean paid;

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

    protected StaffDepartmentBreak() {
    }

    public StaffDepartmentBreak(StaffDepartment department, Store store, LocalTime start, LocalTime end, boolean paid,
            AppUser createdBy) {
        this.department = department;
        this.store = store;
        this.start = start;
        this.end = end;
        this.paid = paid;
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

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public boolean isPaid() {
        return paid;
    }
}
