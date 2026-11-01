package com.intermanage.backend.supplier.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

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

/** Kontakt u dostawcy (przedstawiciel, biuro, księgowość), wspólny dla sklepów firmy. Usunięcie to znacznik. */
@Entity
@Table(name = "supplier_contacts")
public class SupplierContact {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false, updatable = false)
    private SupplierProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierContactKind kind;

    @Column(length = 150)
    private String name;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @Column(length = 50)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected SupplierContact() {
    }

    public SupplierContact(SupplierProfile profile, AppUser createdBy) {
        this.profile = profile;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void update(SupplierContactKind kind, String name, String jobTitle, String phone, String email,
            String notes, AppUser by) {
        this.kind = kind;
        this.name = name;
        this.jobTitle = jobTitle;
        this.phone = phone;
        this.email = email;
        this.notes = notes;
        if (createdAt != null) {
            updatedBy = by;
            updatedAt = Instant.now();
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

    public SupplierProfile getProfile() {
        return profile;
    }

    public SupplierContactKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
