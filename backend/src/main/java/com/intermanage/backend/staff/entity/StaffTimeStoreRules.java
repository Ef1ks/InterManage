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

/**
 * Zasady odbić w sklepie: zaokrąglanie odbić, blokada ponownego odbicia i kto zatwierdza wnioski. Aktywny jeden wpis na
 * sklep, zmiana to znacznik na poprzednim.
 */
@Entity
@Table(name = "staff_time_store_rules")
public class StaffTimeStoreRules {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "rounding_minutes", nullable = false, updatable = false)
    private int roundingMinutes;

    @Column(name = "punch_cooldown_seconds", nullable = false, updatable = false)
    private int punchCooldownSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_approver", nullable = false, updatable = false, length = 20)
    private TimeRequestApprover finalApprover;

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

    /** Same wartości zasad (do porównań, propozycji dla innych sklepów i domyślnych bez wpisu). */
    public record Values(int roundingMinutes, int punchCooldownSeconds, TimeRequestApprover finalApprover) {

        /** Sklep bez zapisanych zasad: bez zaokrąglania, 10 s blokady, zatwierdza kierownik. */
        public static final Values DEFAULT = new Values(0, 10, TimeRequestApprover.MANAGER);
    }

    protected StaffTimeStoreRules() {
    }

    public StaffTimeStoreRules(Store store, Values values, AppUser createdBy) {
        this.store = store;
        this.roundingMinutes = values.roundingMinutes();
        this.punchCooldownSeconds = values.punchCooldownSeconds();
        this.finalApprover = values.finalApprover();
        this.createdBy = createdBy;
    }

    public Values values() {
        return new Values(roundingMinutes, punchCooldownSeconds, finalApprover);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public int getRoundingMinutes() {
        return roundingMinutes;
    }

    public int getPunchCooldownSeconds() {
        return punchCooldownSeconds;
    }

    public TimeRequestApprover getFinalApprover() {
        return finalApprover;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
