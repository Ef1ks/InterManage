package com.intermanage.backend.product.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** Przecena artykułu czekająca na cenówkę (opis w V90). */
@Entity
@Table(name = "price_markdowns")
public class PriceMarkdown {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private String itm8;

    @Column(nullable = false, updatable = false)
    private String ean;

    @Column(updatable = false)
    private String name;

    @Column(name = "regular_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal regularPrice;

    @Column(name = "new_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal newPrice;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false, updatable = false)
    private LocalDate validTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "labels_generated_by")
    private AppUser labelsGeneratedBy;

    @Column(name = "labels_generated_at")
    private Instant labelsGeneratedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PriceMarkdown() {
    }

    public PriceMarkdown(UUID storeId, Product product, BigDecimal newPrice, LocalDate validFrom, LocalDate validTo,
            AppUser createdBy) {
        this.storeId = storeId;
        this.itm8 = product.getItm8();
        this.ean = product.getEan();
        this.name = product.getName();
        this.regularPrice = product.getSalePrice();
        this.newPrice = newPrice;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Kolejny plik z tym samym artykułem zostawia czas pierwszego. */
    public void markLabelsGenerated(AppUser by) {
        if (labelsGeneratedAt == null) {
            labelsGeneratedAt = Instant.now();
            labelsGeneratedBy = by;
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

    public UUID getId() {
        return id;
    }

    public String getItm8() {
        return itm8;
    }

    public String getEan() {
        return ean;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getRegularPrice() {
        return regularPrice;
    }

    public BigDecimal getNewPrice() {
        return newPrice;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLabelsGeneratedAt() {
        return labelsGeneratedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
