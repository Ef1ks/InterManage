package com.intermanage.backend.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.dto.SupplierReportRow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Dostawca sklepu. Z raportu "Lista dostawców" (kluczem jest {@code code}, ten sam co dostawca w liniach dokumentów)
 * albo dodany ręcznie w aplikacji - wtedy bez kodu, bo w systemie sklepu go nie ma.
 */
@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    /** Kod dost. z systemu sklepu; {@code null} u dostawcy dodanego ręcznie. */
    @Column(length = 10, updatable = false)
    private String code;

    @Column(length = 30)
    private String keyword;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 20)
    private String nip;

    @Column(length = 255)
    private String address;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(length = 100)
    private String city;

    @Column(length = 30)
    private String origin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_import_id")
    private SupplierImport lastImport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Supplier() {
    }

    public static Supplier imported(Store store, SupplierReportRow row, SupplierImport supplierImport) {
        Supplier supplier = new Supplier();
        supplier.store = store;
        supplier.code = row.code();
        supplier.apply(row, supplierImport);
        return supplier;
    }

    /** Dostawca spoza systemu sklepu, np. przedstawiciel, który przywozi gratisy. */
    public static Supplier manual(Store store, String name, String nip, AppUser createdBy) {
        Supplier supplier = new Supplier();
        supplier.store = store;
        supplier.name = name;
        supplier.nip = nip;
        supplier.createdBy = createdBy;
        return supplier;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Dane z raportu; {@code true}, gdy któraś się zmieniła. */
    public boolean apply(SupplierReportRow row, SupplierImport supplierImport) {
        lastImport = supplierImport;
        boolean changed = !Objects.equals(keyword, row.keyword())
                || !Objects.equals(name, row.name())
                || !Objects.equals(nip, row.nip())
                || !Objects.equals(address, row.address())
                || !Objects.equals(postalCode, row.postalCode())
                || !Objects.equals(city, row.city())
                || !Objects.equals(origin, row.origin());
        keyword = row.keyword();
        name = row.name();
        nip = row.nip();
        address = row.address();
        postalCode = row.postalCode();
        city = row.city();
        origin = row.origin();
        if (changed && createdAt != null) {
            updatedAt = Instant.now();
        }
        return changed;
    }

    public boolean isManual() {
        return code == null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getCode() {
        return code;
    }

    public String getKeyword() {
        return keyword;
    }

    public String getName() {
        return name;
    }

    public String getNip() {
        return nip;
    }

    public String getAddress() {
        return address;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCity() {
        return city;
    }

    public String getOrigin() {
        return origin;
    }

    public SupplierImport getLastImport() {
        return lastImport;
    }
}
