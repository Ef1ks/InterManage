package com.intermanage.backend.store.entity;

import java.time.Instant;
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

/**
 * Para sklepów zaprzyjaźnionych: {@code store} zaprosił {@code partnerStore}, a ten zaakceptował
 * ({@code acceptedAt}). Zaakceptowana para działa w obie strony. Odrzucenie, cofnięcie zaproszenia i zakończenie
 * znajomości to znacznik {@code deletedAt}, który da się cofnąć.
 */
@Entity
@Table(name = "store_partners")
public class StorePartner {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partner_store_id", nullable = false, updatable = false)
    private Store partnerStore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by", updatable = false)
    private AppUser invitedBy;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    private AppUser acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StorePartner() {
    }

    public StorePartner(Store store, Store partnerStore, AppUser invitedBy) {
        this.store = store;
        this.partnerStore = partnerStore;
        this.invitedBy = invitedBy;
    }

    @PrePersist
    void onCreate() {
        invitedAt = Instant.now();
    }

    public void accept(AppUser by) {
        acceptedAt = Instant.now();
        acceptedBy = by;
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /** Drugi sklep pary, patrząc od strony {@code storeId}. */
    public Store otherThan(UUID storeId) {
        return store.getId().equals(storeId) ? partnerStore : store;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public Store getPartnerStore() {
        return partnerStore;
    }

    public AppUser getInvitedBy() {
        return invitedBy;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public AppUser getAcceptedBy() {
        return acceptedBy;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
