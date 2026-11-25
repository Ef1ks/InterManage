package com.intermanage.backend.collector.entity;

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

/** Drukarka Bluetooth sklepu; kolektor łączy się z nią po adresie MAC. */
@Entity
@Table(name = "store_printers")
public class StorePrinter {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 12)
    private String mac;

    @Column(length = 60)
    private String model;

    @Column(length = 16)
    private String pin;

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

    protected StorePrinter() {
    }

    public StorePrinter(Store store, String name, String mac, String model, String pin, AppUser createdBy) {
        this.store = store;
        this.name = name;
        this.mac = mac;
        this.model = model;
        this.pin = pin;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void change(String newName, String newMac, String newModel, String newPin, AppUser by) {
        name = newName;
        mac = newMac;
        model = newModel;
        pin = newPin;
        updatedBy = by;
        updatedAt = Instant.now();
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

    public Store getStore() {
        return store;
    }

    public String getName() {
        return name;
    }

    public String getMac() {
        return mac;
    }

    public String getModel() {
        return model;
    }

    public String getPin() {
        return pin;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
