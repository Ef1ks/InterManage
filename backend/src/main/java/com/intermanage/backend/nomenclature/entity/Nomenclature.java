package com.intermanage.backend.nomenclature.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

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
 * Pozycja nomenklatury sklepu: rejon, rodzina albo podrodzina. Produkt należy do podrodziny
 * ({@code products.subfamily_code}). Znaczenie kolumn opisuje migracja V24__nomenclatures_and_sales.sql.
 */
@Entity
@Table(name = "nomenclatures")
public class Nomenclature {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 6, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private NomenclatureLevel level;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", updatable = false)
    private Nomenclature parent;

    /** Liczone przez bazę z {@code code}; po zapisie nowej pozycji w tej samej sesji puste. */
    @Column(name = "region_code", length = 2, insertable = false, updatable = false)
    private String regionCode;

    /** Liczone przez bazę z {@code code}, puste u rejonu. */
    @Column(name = "family_code", length = 4, insertable = false, updatable = false)
    private String familyCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "vat_code")
    private Short vatCode;

    @Column(length = 10)
    private String unit;

    @Column(name = "label_count")
    private Short labelCount;

    @Column(name = "label_format")
    private Short labelFormat;

    @Column(name = "till_mode", length = 30)
    private String tillMode;

    @Column(name = "sale_type", length = 30)
    private String saleType;

    @Column(name = "stock_mode", length = 30)
    private String stockMode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_import_id", nullable = false)
    private NomenclatureImport lastImport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Nomenclature() {
    }

    /** Rodzic musi mieć kod krótszy o dwie cyfry; rejon nie ma rodzica. */
    public Nomenclature(Store store, String code, Nomenclature parent, NomenclatureImport lastImport) {
        this.level = NomenclatureLevel.ofCode(code);
        boolean fitsParent = parent == null
                ? level == NomenclatureLevel.REGION
                : code.length() == parent.code.length() + 2 && code.startsWith(parent.code);
        if (!fitsParent) {
            throw new IllegalArgumentException("Nomenklatura " + code + " nie pasuje do rodzica "
                    + (parent == null ? "(brak)" : parent.code));
        }
        this.store = store;
        this.code = code;
        this.parent = parent;
        this.lastImport = lastImport;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Dane z raportu; {@code true}, gdy któraś się zmieniła. */
    public boolean update(String name, Short vatCode, String unit, Short labelCount, Short labelFormat,
            String tillMode, String saleType, String stockMode) {
        boolean changed = !Objects.equals(this.name, name)
                || !Objects.equals(this.vatCode, vatCode)
                || !Objects.equals(this.unit, unit)
                || !Objects.equals(this.labelCount, labelCount)
                || !Objects.equals(this.labelFormat, labelFormat)
                || !Objects.equals(this.tillMode, tillMode)
                || !Objects.equals(this.saleType, saleType)
                || !Objects.equals(this.stockMode, stockMode);
        if (changed) {
            this.name = name;
            this.vatCode = vatCode;
            this.unit = unit;
            this.labelCount = labelCount;
            this.labelFormat = labelFormat;
            this.tillMode = tillMode;
            this.saleType = saleType;
            this.stockMode = stockMode;
            if (createdAt != null) {
                updatedAt = Instant.now();
            }
        }
        return changed;
    }

    public void markSeen(NomenclatureImport nomenclatureImport) {
        this.lastImport = nomenclatureImport;
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

    public NomenclatureLevel getLevel() {
        return level;
    }

    public Nomenclature getParent() {
        return parent;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public String getFamilyCode() {
        return familyCode;
    }

    public String getName() {
        return name;
    }

    public Short getVatCode() {
        return vatCode;
    }

    public String getUnit() {
        return unit;
    }

    public Short getLabelCount() {
        return labelCount;
    }

    public Short getLabelFormat() {
        return labelFormat;
    }

    public String getTillMode() {
        return tillMode;
    }

    public String getSaleType() {
        return saleType;
    }

    public String getStockMode() {
        return stockMode;
    }

    public NomenclatureImport getLastImport() {
        return lastImport;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
