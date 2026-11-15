package com.intermanage.backend.mea.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Pozycja formularza MEA. {@code meaItm8} to ITM8 promocyjny (8 cyfr, bez zer wiodących), pod którym towar przychodzi
 * w przyjęciu PZ; {@code parallelItm8} to stały artykuł z cadencier (CAD w eMEA).
 */
@Entity
@Table(name = "mea_document_lines")
public class MeaDocumentLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private MeaDocument document;

    @Column(nullable = false)
    private int lp;

    @Column(name = "mea_itm8", nullable = false)
    private String meaItm8;

    @Column(name = "parallel_itm8")
    private String parallelItm8;

    private String ean;
    private String name;
    private String brand;

    @Column(name = "packs_per_pallet")
    private Integer packsPerPallet;

    @Column(name = "units_per_pack")
    private Integer unitsPerPack;

    @Column(name = "vat_percent")
    private Integer vatPercent;

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "parallel_purchase_price", precision = 12, scale = 2)
    private BigDecimal parallelPurchasePrice;

    @Column(name = "shelf_price", precision = 12, scale = 2)
    private BigDecimal shelfPrice;

    @Column(name = "parallel_shelf_price", precision = 12, scale = 2)
    private BigDecimal parallelShelfPrice;

    @Column(precision = 8, scale = 2)
    private BigDecimal margin;

    @Column(name = "parallel_margin", precision = 8, scale = 2)
    private BigDecimal parallelMargin;

    @Column(name = "guaranteed_days")
    private Integer guaranteedDays;

    private String note;

    protected MeaDocumentLine() {
    }

    public MeaDocumentLine(int lp, String meaItm8, String parallelItm8, String ean, String name, String brand,
            Integer packsPerPallet, Integer unitsPerPack, Integer vatPercent, BigDecimal purchasePrice,
            BigDecimal parallelPurchasePrice, BigDecimal shelfPrice, BigDecimal parallelShelfPrice,
            BigDecimal margin, BigDecimal parallelMargin, Integer guaranteedDays, String note) {
        this.lp = lp;
        this.meaItm8 = meaItm8;
        this.parallelItm8 = parallelItm8;
        this.ean = cut(ean, 20);
        this.name = cut(name, 200);
        this.brand = cut(brand, 100);
        this.packsPerPallet = packsPerPallet;
        this.unitsPerPack = unitsPerPack;
        this.vatPercent = vatPercent;
        this.purchasePrice = purchasePrice;
        this.parallelPurchasePrice = parallelPurchasePrice;
        this.shelfPrice = shelfPrice;
        this.parallelShelfPrice = parallelShelfPrice;
        this.margin = margin;
        this.parallelMargin = parallelMargin;
        this.guaranteedDays = guaranteedDays;
        this.note = note;
    }

    void attach(MeaDocument document) {
        this.document = document;
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    public int getLp() {
        return lp;
    }

    public String getMeaItm8() {
        return meaItm8;
    }

    public String getParallelItm8() {
        return parallelItm8;
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

    public Integer getPacksPerPallet() {
        return packsPerPallet;
    }

    public Integer getUnitsPerPack() {
        return unitsPerPack;
    }

    public Integer getVatPercent() {
        return vatPercent;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getParallelPurchasePrice() {
        return parallelPurchasePrice;
    }

    public BigDecimal getShelfPrice() {
        return shelfPrice;
    }

    public BigDecimal getParallelShelfPrice() {
        return parallelShelfPrice;
    }

    public BigDecimal getMargin() {
        return margin;
    }

    public BigDecimal getParallelMargin() {
        return parallelMargin;
    }

    public Integer getGuaranteedDays() {
        return guaranteedDays;
    }

    public String getNote() {
        return note;
    }
}
