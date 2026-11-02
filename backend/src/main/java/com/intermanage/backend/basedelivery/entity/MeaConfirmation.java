package com.intermanage.backend.basedelivery.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Potwierdzenie wpisania kodów MEA w Mercalys (opis w V71__base_deliveries.sql). Cofnięcie to znacznik na
 * potwierdzeniu i jego kodach, przywrócenie go zdejmuje.
 */
@Entity
@Table(name = "mea_confirmations")
public class MeaConfirmation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by", updatable = false)
    private AppUser confirmedBy;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "confirmation", cascade = CascadeType.PERSIST)
    @OrderBy("code")
    private List<MeaCode> codes = new ArrayList<>();

    protected MeaConfirmation() {
    }

    public MeaConfirmation(Store store, AppUser by) {
        this.store = store;
        this.confirmedBy = by;
    }

    @PrePersist
    void onCreate() {
        confirmedAt = Instant.now();
    }

    public void addCode(String code) {
        codes.add(new MeaCode(store, this, code));
    }

    public void delete(AppUser by) {
        Instant now = Instant.now();
        deletedAt = now;
        deletedBy = by;
        codes.forEach(code -> code.delete(now));
    }

    /** Zdejmuje znacznik z potwierdzenia i z kodów, których nie potwierdzono w międzyczasie ponownie. */
    public void restore(Set<String> activeElsewhere) {
        deletedAt = null;
        deletedBy = null;
        codes.stream().filter(code -> !activeElsewhere.contains(code.getCode())).forEach(MeaCode::restore);
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public AppUser getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public AppUser getDeletedBy() {
        return deletedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public List<MeaCode> getCodes() {
        return codes;
    }
}
