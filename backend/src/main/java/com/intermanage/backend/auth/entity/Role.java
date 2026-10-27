package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** Systemowa rola właściciela: ma wszystkie uprawnienia i nie da się jej edytować ani usunąć. */
    @Column(nullable = false)
    private boolean owner;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @MapKeyColumn(name = "category")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    @Enumerated(EnumType.STRING)
    private Map<PermissionCategory, PermissionLevel> permissions = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Role() {
    }

    public Role(String name, String description) {
        this.name = name;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Efektywne uprawnienia: właściciel dostaje EDIT do każdej kategorii. */
    public Map<PermissionCategory, PermissionLevel> effectivePermissions() {
        Map<PermissionCategory, PermissionLevel> result = new EnumMap<>(PermissionCategory.class);
        if (owner) {
            for (PermissionCategory category : PermissionCategory.values()) {
                result.put(category, PermissionLevel.EDIT);
            }
        } else {
            result.putAll(permissions);
        }
        return result;
    }

    public UUID getId() {
        return id;
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

    public boolean isOwner() {
        return owner;
    }

    public Map<PermissionCategory, PermissionLevel> getPermissions() {
        return permissions;
    }

    public void replacePermissions(Map<PermissionCategory, PermissionLevel> newPermissions) {
        permissions.clear();
        permissions.putAll(newPermissions);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
