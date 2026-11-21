package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Ranga konta w sklepie: jeden wiersz na konto i sklep, nadpisywany przy zmianie. */
@Entity
@Table(name = "user_store_roles")
public class UserStoreRole {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_role_id", nullable = false)
    private StoreRole role;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private AppUser assignedBy;

    protected UserStoreRole() {
    }

    public UserStoreRole(AppUser user, StoreRole role, AppUser assignedBy) {
        this.user = user;
        this.store = role.getStore();
        this.role = role;
        this.assignedAt = Instant.now();
        this.assignedBy = assignedBy;
    }

    public void change(StoreRole newRole, AppUser by) {
        this.role = newRole;
        this.assignedAt = Instant.now();
        this.assignedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Store getStore() {
        return store;
    }

    public StoreRole getRole() {
        return role;
    }
}
