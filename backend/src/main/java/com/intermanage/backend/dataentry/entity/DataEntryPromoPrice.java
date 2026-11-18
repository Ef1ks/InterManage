package com.intermanage.backend.dataentry.entity;

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

/** Cena zwykła artykułu w promocji w kolejce do wpisania w taryfie Mercalys (opis w V166). */
@Entity
@Table(name = "data_entry_promo_prices")
public class DataEntryPromoPrice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private String ean;

    @Column(nullable = false, updatable = false)
    private String itm8;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(name = "sale_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "vat_code", nullable = false, updatable = false)
    private String vatCode;

    @Column(name = "promo_price", updatable = false, precision = 12, scale = 2)
    private BigDecimal promoPrice;

    @Column(name = "promo_to", updatable = false)
    private LocalDate promoTo;

    @Column(updatable = false)
    private String document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by")
    private AppUser generatedBy;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DataEntryPromoPrice() {
    }

    public DataEntryPromoPrice(UUID storeId, String ean, String itm8, String name, BigDecimal salePrice,
            String vatCode, BigDecimal promoPrice, LocalDate promoTo, String document, AppUser createdBy) {
        this.storeId = storeId;
        this.ean = ean;
        this.itm8 = itm8;
        this.name = name;
        this.salePrice = salePrice;
        this.vatCode = vatCode;
        this.promoPrice = promoPrice;
        this.promoTo = promoTo;
        this.document = document;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Ponowne wygenerowanie makra zostawia czas pierwszego. */
    public void markGenerated(AppUser by) {
        if (generatedAt == null) {
            generatedAt = Instant.now();
            generatedBy = by;
        }
    }

    public void delete(AppUser by) {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            deletedBy = by;
        }
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getEan() {
        return ean;
    }

    public String getItm8() {
        return itm8;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public String getVatCode() {
        return vatCode;
    }

    public BigDecimal getPromoPrice() {
        return promoPrice;
    }

    public LocalDate getPromoTo() {
        return promoTo;
    }

    public String getDocument() {
        return document;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
