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

/**
 * Najmniejsza i docelowa obsada stanowiska na dzień w danym sklepie (co najmniej jedna z nich). Grafik tylko
 * podpowiada. Zmiana i usunięcie to znacznik.
 */
@Entity
@Table(name = "staff_position_minimums")
public class StaffPositionMinimum {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "position_id", nullable = false, updatable = false)
    private StaffPosition position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "min_count", updatable = false)
    private Integer minCount;

    @Column(name = "target_count", updatable = false)
    private Integer targetCount;

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

    protected StaffPositionMinimum() {
    }

    public StaffPositionMinimum(StaffPosition position, Store store, Integer minCount, Integer targetCount,
            AppUser createdBy) {
        this.position = position;
        this.store = store;
        this.minCount = minCount;
        this.targetCount = targetCount;
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

    public StaffPosition getPosition() {
        return position;
    }

    public Integer getMinCount() {
        return minCount;
    }

    public Integer getTargetCount() {
        return targetCount;
    }
}
