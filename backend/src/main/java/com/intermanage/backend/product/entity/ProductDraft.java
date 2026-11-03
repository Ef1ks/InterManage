package com.intermanage.backend.product.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.supplier.entity.Supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Szkic nowego artykułu z pliku CADBEZP, potwierdzony przez pracownika. Czeka na pierwszy raport kartoteki
 * wygenerowany po potwierdzeniu: z artykułem o tym EAN-ie w raporcie szkic jest dopasowany do {@link Product}, bez
 * niego usunięty ({@link RemovedReason#MISSING}). Znaczenie kolumn opisuje migracja V72__product_drafts.sql.
 */
@Entity
@Table(name = "product_drafts")
public class ProductDraft {

    /** Dlaczego szkic przestał czekać bez dopasowania. Nazwa trafia do bazy. */
    public enum RemovedReason {
        /** Raport kartoteki po potwierdzeniu nie ma artykułu z tym EAN-em. */
        MISSING,
        /** Pracownik cofnął potwierdzenie. */
        CANCELLED,
        /** Nowszy szkic tego samego EAN-u. */
        REPLACED
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 14, updatable = false)
    private String ean;

    @Column(updatable = false)
    private String name;

    @Column(updatable = false)
    private String brand;

    @Column(updatable = false)
    private String unit;

    @Column(name = "net_content", precision = 12, scale = 3, updatable = false)
    private BigDecimal netContent;

    @Column(name = "purchase_price", precision = 12, scale = 4, updatable = false)
    private BigDecimal purchasePrice;

    @Column(name = "vat_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal vatPercent;

    @Column(name = "sale_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal salePrice;

    @Column(name = "subfamily_code", updatable = false)
    private String subfamilyCode;

    @Column(updatable = false)
    private String cn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", updatable = false)
    private Supplier supplier;

    @Column(name = "supplier_name", updatable = false)
    private String supplierName;

    @Column(name = "invoice_number", updatable = false)
    private String invoiceNumber;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    /** Szkic tego samego EAN-u, który ten zastąpił; cofnięcie potwierdzenia go przywraca. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_id", updatable = false)
    private ProductDraft replaces;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_product_id")
    private Product matchedProduct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_import_id")
    private ProductImport matchedImport;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "removed_reason")
    private RemovedReason removedReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_import_id")
    private ProductImport removedImport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected ProductDraft() {
    }

    public ProductDraft(Store store, String ean, String name, String brand, String unit, BigDecimal netContent,
            BigDecimal purchasePrice, BigDecimal vatPercent, BigDecimal salePrice, String subfamilyCode, String cn,
            Supplier supplier, String supplierName, String invoiceNumber, String fileName, ProductDraft replaces,
            AppUser createdBy) {
        this.store = store;
        this.ean = ean;
        this.name = name;
        this.brand = brand;
        this.unit = unit;
        this.netContent = netContent;
        this.purchasePrice = purchasePrice;
        this.vatPercent = vatPercent;
        this.salePrice = salePrice;
        this.subfamilyCode = subfamilyCode;
        this.cn = cn;
        this.supplier = supplier;
        this.supplierName = supplierName;
        this.invoiceNumber = invoiceNumber;
        this.fileName = fileName;
        this.replaces = replaces;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isPending() {
        return matchedAt == null && removedAt == null;
    }

    /** Raport kartoteki ma artykuł z EAN-em szkicu: dalsze dane artykułu są w {@code product}. */
    public void matched(Product product, ProductImport productImport) {
        matchedProduct = product;
        matchedImport = productImport;
        matchedAt = Instant.now();
    }

    public void removed(RemovedReason reason, ProductImport productImport, AppUser by) {
        removedReason = reason;
        removedImport = productImport;
        removedBy = by;
        removedAt = Instant.now();
    }

    /** Przywraca szkic zastąpiony przez cofnięty; inaczej usunięty zostaje. */
    public void restoreReplaced() {
        if (removedReason == RemovedReason.REPLACED) {
            removedReason = null;
            removedBy = null;
            removedAt = null;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getEan() {
        return ean;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getNetContent() {
        return netContent;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getVatPercent() {
        return vatPercent;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public String getSubfamilyCode() {
        return subfamilyCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ProductDraft getReplaces() {
        return replaces;
    }
}
