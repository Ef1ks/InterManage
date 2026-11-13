package com.intermanage.backend.ksef.entity;

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
 * Kod dostawcy w bazie SCA PR (z refaktur, "DDDKodDostawcy") przypisany do dostawcy, wspólnie dla firmy (V133).
 * {@code code} bez zer wiodących, {@code name} to nazwa, po której szuka się faktur, {@code supplierId} i
 * {@code supplierNip} - dostawca z listy dostawców sklepu, gdy wybrano go z listy. Zmiana i usunięcie to znacznik.
 */
@Entity
@Table(name = "ksef_base_supplier_codes")
public class KsefBaseSupplierCode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 10)
    private String nip;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, updatable = false, length = 500)
    private String name;

    @Column(name = "supplier_nip", updatable = false, length = 20)
    private String supplierNip;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

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

    protected KsefBaseSupplierCode() {
    }

    public KsefBaseSupplierCode(String nip, String code, String name, String supplierNip, UUID supplierId,
            AppUser by) {
        this.nip = nip;
        this.code = normalize(code);
        this.name = name;
        this.supplierNip = supplierNip;
        this.supplierId = supplierId;
        this.createdBy = by;
    }

    /** Kod bez zer wiodących i spacji ("09295" → "9295"); z samych zer zostaje "0". */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String stripped = code.strip().replaceFirst("^0+", "");
        return stripped.isEmpty() && !code.isBlank() ? "0" : stripped;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getNip() {
        return nip;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSupplierNip() {
        return supplierNip;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
