package com.intermanage.backend.basedelivery.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Tymczasowy bon dostawy (Excel z Mercalys) przysłany przez agenta po makrze; nowszy daje poprzedniemu znacznik. */
@Entity
@Table(name = "base_delivery_notes")
public class BaseDeliveryNote {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "bon_number", nullable = false, length = 20, updatable = false)
    private String bonNumber;

    @Column(name = "supplier_code", nullable = false, length = 10, updatable = false)
    private String supplierCode;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    protected BaseDeliveryNote() {
    }

    public BaseDeliveryNote(Store store, String bonNumber, String supplierCode, String fileName, byte[] content) {
        this.store = store;
        this.bonNumber = bonNumber;
        this.supplierCode = supplierCode;
        this.fileName = fileName;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        uploadedAt = Instant.now();
    }

    public void replace() {
        replacedAt = Instant.now();
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
