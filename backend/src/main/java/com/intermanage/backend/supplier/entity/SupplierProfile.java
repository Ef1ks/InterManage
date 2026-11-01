package com.intermanage.backend.supplier.entity;

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
 * Dane dostawcy spoza raportu (strona, telefon, e-mail, notatki, kontakty), wspólne dla sklepów jednej firmy.
 * {@code companyKey} to NIP firmy sklepu, a {@code supplierKey} wskazuje dostawcę w każdym jej sklepie.
 */
@Entity
@Table(name = "supplier_profiles")
public class SupplierProfile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "company_key", nullable = false, length = 60, updatable = false)
    private String companyKey;

    @Column(name = "supplier_key", nullable = false, length = 220, updatable = false)
    private String supplierKey;

    @Column(length = 500)
    private String website;

    @Column(length = 200)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected SupplierProfile() {
    }

    public SupplierProfile(String companyKey, String supplierKey) {
        this.companyKey = companyKey;
        this.supplierKey = supplierKey;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void update(String website, String email, String phone, String notes, AppUser by) {
        this.website = website;
        this.email = email;
        this.phone = phone;
        this.notes = notes;
        updatedBy = by;
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSupplierKey() {
        return supplierKey;
    }

    public String getWebsite() {
        return website;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getNotes() {
        return notes;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
