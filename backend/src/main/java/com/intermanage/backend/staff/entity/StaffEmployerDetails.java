package com.intermanage.backend.staff.entity;

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

/** Dane pracodawcy do wydruków kadrowych, wspólne dla firmy (NIP). Zmiana to znacznik i nowy wpis. */
@Entity
@Table(name = "staff_employer_details")
public class StaffEmployerDetails {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "company_key", nullable = false, length = 64, updatable = false)
    private String companyKey;

    @Column(nullable = false, length = 200, updatable = false)
    private String name;

    @Column(length = 300, updatable = false)
    private String address;

    @Column(length = 10, updatable = false)
    private String nip;

    @Column(length = 14, updatable = false)
    private String regon;

    @Column(length = 200, updatable = false)
    private String representative;

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

    protected StaffEmployerDetails() {
    }

    public StaffEmployerDetails(String companyKey, String name, String address, String nip, String regon, String representative, AppUser createdBy) {
        this.companyKey = companyKey;
        this.name = name;
        this.address = address;
        this.nip = nip;
        this.regon = regon;
        this.representative = representative;
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

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getNip() {
        return nip;
    }

    public String getRegon() {
        return regon;
    }

    public String getRepresentative() {
        return representative;
    }
}
