package com.intermanage.backend.staff.entity;

import java.time.Instant;
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

/** Przeniesienie pracownika do innego sklepu firmy. Historia, wpisów się nie zmienia. */
@Entity
@Table(name = "staff_employee_moves")
public class StaffEmployeeMove {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_store_id", nullable = false, updatable = false)
    private Store fromStore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_store_id", nullable = false, updatable = false)
    private Store toStore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_position_id", nullable = false, updatable = false)
    private StaffPosition fromPosition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_position_id", nullable = false, updatable = false)
    private StaffPosition toPosition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moved_by", updatable = false)
    private AppUser movedBy;

    @Column(name = "moved_at", nullable = false, updatable = false)
    private Instant movedAt;

    protected StaffEmployeeMove() {
    }

    public StaffEmployeeMove(StaffEmployee employee, Store fromStore, Store toStore, StaffPosition fromPosition,
            StaffPosition toPosition, AppUser movedBy) {
        this.employee = employee;
        this.fromStore = fromStore;
        this.toStore = toStore;
        this.fromPosition = fromPosition;
        this.toPosition = toPosition;
        this.movedBy = movedBy;
    }

    @PrePersist
    void onCreate() {
        movedAt = Instant.now();
    }
}
