package com.intermanage.backend.deliproduction.entity;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Artykuł bazy kart produkcji: kod (drukowany na formularzu i skanowany), nazwa na karcie i schemat parametrów
 * procesu. Usunięcie to znacznik.
 */
@Entity
@Table(name = "production_articles")
public class ProductionArticle {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(length = 20)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 20)
    private String nomenclature;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schema_id", nullable = false)
    private ProductionSchema schema;

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

    protected ProductionArticle() {
    }

    public ProductionArticle(UUID storeId, AppUser by) {
        this.storeId = storeId;
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

    public void update(String code, String name, String nomenclature, ProductionSchema schema, AppUser by) {
        this.code = code;
        this.name = name;
        this.nomenclature = nomenclature;
        this.schema = schema;
        this.updatedBy = by;
    }

    public void changeSchema(ProductionSchema schema, AppUser by) {
        this.schema = schema;
        this.updatedBy = by;
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

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getNomenclature() {
        return nomenclature;
    }

    public ProductionSchema getSchema() {
        return schema;
    }
}
