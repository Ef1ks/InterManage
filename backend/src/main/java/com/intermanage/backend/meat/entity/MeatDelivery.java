package com.intermanage.backend.meat.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.supplier.entity.Supplier;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Dokument WZ dostawcy mięsa z cenami zakupu netto za kg (opis w V63__meat_deliveries.sql). Dostawca to firma
 * z dokumentu, nie SCA PR, które fakturuje. Usunięcie to znacznik.
 */
@Entity
@Table(name = "meat_deliveries")
public class MeatDelivery {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "document_number", nullable = false, length = 100)
    private String documentNumber;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", updatable = false)
    private MeatDeliveryScan scan;

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<MeatDeliveryLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MeatDelivery() {
    }

    public MeatDelivery(UUID storeId, MeatDeliveryScan scan, AppUser by) {
        this.storeId = storeId;
        this.scan = scan;
        this.createdBy = by;
        this.updatedBy = by;
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

    /** Nagłówek i pozycje z formularza; pozycje zastępują poprzednie. */
    public void update(Supplier supplier, String documentNumber, LocalDate deliveryDate, List<MeatDeliveryLine> newLines,
            AppUser by) {
        this.supplier = supplier;
        this.documentNumber = documentNumber;
        this.deliveryDate = deliveryDate;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
        lines.clear();
        for (MeatDeliveryLine line : newLines) {
            line.attachTo(this, lines.size() + 1);
            lines.add(line);
        }
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

    public UUID getStoreId() {
        return storeId;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public MeatDeliveryScan getScan() {
        return scan;
    }

    public List<MeatDeliveryLine> getLines() {
        return lines;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
