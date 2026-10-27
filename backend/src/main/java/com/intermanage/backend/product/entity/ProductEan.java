package com.intermanage.backend.product.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Para ITM8 + EAN, która wystąpiła w raporcie. Nigdy nie jest nadpisywana ani usuwana: sprzedaż zapisana pod tą parą
 * zostaje przy właściwym artykule, nawet gdy EAN przejdzie do innego ITM8 albo zostanie poprawiony.
 */
@Entity
@Table(name = "product_eans")
public class ProductEan {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, length = 14, updatable = false)
    private String ean;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_seen_import_id", nullable = false, updatable = false)
    private ProductImport firstSeenImport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_seen_import_id", nullable = false)
    private ProductImport lastSeenImport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductEan() {
    }

    public ProductEan(Product product, String ean, ProductImport productImport) {
        this.product = product;
        this.ean = ean;
        this.firstSeenImport = productImport;
        this.lastSeenImport = productImport;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void seenIn(ProductImport productImport) {
        lastSeenImport = productImport;
    }

    /**
     * Czy ta para występowała później niż {@code other}: nowszy ostatni import, przy remisie nowszy pierwszy import,
     * a na końcu później utworzona para. Kolejność z dat, nie z identyfikatorów.
     */
    public boolean seenLaterThan(ProductEan other) {
        int byLastImport = lastSeenImport.getImportedAt().compareTo(other.lastSeenImport.getImportedAt());
        if (byLastImport != 0) {
            return byLastImport > 0;
        }
        int byFirstImport = firstSeenImport.getImportedAt().compareTo(other.firstSeenImport.getImportedAt());
        if (byFirstImport != 0) {
            return byFirstImport > 0;
        }
        return createdAt != null && other.createdAt != null && createdAt.isAfter(other.createdAt);
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getEan() {
        return ean;
    }
}
