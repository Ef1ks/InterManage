package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Formula;
import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Ranga w hierarchii sklepu: rola z uprawnieniami, ułożona od najwyższej ({@link #position} 0). Aderon
 * ({@link #admin}) ma w sklepie wszystkie uprawnienia, ale nie jest właścicielem aplikacji. Rangi poza hierarchią
 * (Księgowość, Kadry) mają tylko swoje uprawnienia: nikim nie zarządzają, a nimi zarządza Aderon i właściciel.
 */
@Entity
@Table(name = "store_roles")
public class StoreRole {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "role_key", nullable = false, length = 80, updatable = false)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, updatable = false)
    private boolean admin;

    @Column(name = "outside_hierarchy", nullable = false)
    private boolean outsideHierarchy;

    @Column(nullable = false)
    private int position;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "store_role_permissions", joinColumns = @JoinColumn(name = "store_role_id"))
    @MapKeyColumn(name = "category")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    @Enumerated(EnumType.STRING)
    private Map<PermissionCategory, PermissionLevel> permissions = new HashMap<>();

    /** Ile rang w hierarchii sklepu stoi niżej: ranga bez nikogo pod sobą nie zleca zadań. */
    @Formula("(select count(*) from store_roles r where r.store_id = store_id and r.deleted_at is null"
            + " and not r.outside_hierarchy and r.position > position)")
    private int lowerRanks;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    protected StoreRole() {
    }

    public StoreRole(Store store, String key, String name, String description, boolean admin,
            boolean outsideHierarchy, int position, AppUser createdBy) {
        this.store = store;
        this.key = key;
        this.name = name;
        this.description = description;
        this.admin = admin;
        this.outsideHierarchy = !admin && outsideHierarchy;
        this.position = position;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Uprawnienia w sklepie: Aderon dostaje EDIT do każdej kategorii. */
    public Map<PermissionCategory, PermissionLevel> effectivePermissions() {
        Map<PermissionCategory, PermissionLevel> result = new EnumMap<>(PermissionCategory.class);
        if (admin) {
            for (PermissionCategory category : PermissionCategory.values()) {
                result.put(category, PermissionLevel.EDIT);
            }
        } else {
            result.putAll(permissions);
        }
        return result;
    }

    /** Czy ta ranga stoi w hierarchii wyżej niż {@code other} (Aderon nad każdą, poza hierarchią nad nikim). */
    public boolean isAbove(StoreRole other) {
        if (other == null) {
            return !outsideHierarchy;
        }
        // Gettery, nie pola: druga ranga bywa leniwym proxy Hibernate, którego pola są puste.
        if (admin) {
            return !other.isAdmin();
        }
        return !outsideHierarchy && !other.isOutsideHierarchy() && !other.isAdmin() && position < other.getPosition();
    }

    public void replacePermissions(Map<PermissionCategory, PermissionLevel> newPermissions) {
        permissions.clear();
        if (!admin) {
            permissions.putAll(newPermissions);
        }
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

    public Store getStore() {
        return store;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isAdmin() {
        return admin;
    }

    public boolean isOutsideHierarchy() {
        return outsideHierarchy;
    }

    public void setOutsideHierarchy(boolean outsideHierarchy) {
        this.outsideHierarchy = !admin && outsideHierarchy;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Map<PermissionCategory, PermissionLevel> getPermissions() {
        return permissions;
    }

    public int getLowerRanks() {
        return lowerRanks;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
