package com.intermanage.backend.basedelivery.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Zamówienie w Mercalys, na które wszedł plik bonu jednego dostawcy (V189). Nowy numer daje poprzedniemu znacznik. */
@Entity
@Table(name = "base_delivery_orders")
public class BaseDeliveryOrder {

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

    @Column(name = "order_number", nullable = false, length = 20, updatable = false)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entered_by", updatable = false)
    private AppUser enteredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected BaseDeliveryOrder() {
    }

    public BaseDeliveryOrder(Store store, String bonNumber, String supplierCode, String orderNumber,
            AppUser enteredBy) {
        this.store = store;
        this.bonNumber = bonNumber;
        this.supplierCode = supplierCode;
        this.orderNumber = orderNumber;
        this.enteredBy = enteredBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void replace() {
        replacedAt = Instant.now();
    }

    public void close() {
        if (closedAt == null) {
            closedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getBonNumber() {
        return bonNumber;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public AppUser getEnteredBy() {
        return enteredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
