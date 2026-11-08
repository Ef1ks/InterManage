package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Stanowisko w dziale. Jego rola to uprawnienia w aplikacji wszystkich pracowników na tym stanowisku. */
@Entity
@Table(name = "staff_positions")
public class StaffPosition {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private StaffDepartment department;

    @Column(nullable = false, length = 80)
    private String name;

    /** Pusta tylko w usuniętym stanowisku, którego rolę później usunięto. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

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

    protected StaffPosition() {
    }

    public StaffPosition(StaffDepartment department, String name, Role role, AppUser createdBy) {
        this.department = department;
        this.name = name;
        this.role = role;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void update(StaffDepartment department, String name, Role role) {
        this.department = department;
        this.name = name;
        this.role = role;
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

    public StaffDepartment getDepartment() {
        return department;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
