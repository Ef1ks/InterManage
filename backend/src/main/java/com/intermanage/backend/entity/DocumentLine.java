package com.intermanage.backend.entity;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.dto.DocumentLineRow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Linia dokumentu (N° ligne + ITM8). Kwoty jak w raporcie, z dokładnością kolumn w bazie. */
@Entity
@Table(name = "document_lines")
public class DocumentLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Column(nullable = false, length = 13, updatable = false)
    private String itm8;

    @Column(name = "supplier_code", length = 10)
    private String supplierCode;

    @Column(name = "supplier_name", length = 100)
    private String supplierName;

    @Column(name = "nomenclature_code", length = 30)
    private String nomenclatureCode;

    @Column(name = "nomenclature_name", length = 60)
    private String nomenclatureName;

    @Column(length = 14)
    private String ean;

    @Column(length = 100)
    private String name;

    @Column(length = 50)
    private String brand;

    @Column(length = 20)
    private String unit;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "purchase_price", precision = 14, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "sale_price_current", precision = 14, scale = 4)
    private BigDecimal salePriceCurrent;

    @Column(name = "sale_price_document", precision = 14, scale = 4)
    private BigDecimal salePriceDocument;

    @Column(name = "purchase_value", precision = 16, scale = 4)
    private BigDecimal purchaseValue;

    @Column(name = "sale_value_gross", precision = 16, scale = 4)
    private BigDecimal saleValueGross;

    @Column(name = "sale_value_net", precision = 16, scale = 4)
    private BigDecimal saleValueNet;

    @Column(name = "vat_rate")
    private Short vatRate;

    @Column(name = "vat_value", precision = 16, scale = 4)
    private BigDecimal vatValue;

    @Column(name = "margin_value", precision = 16, scale = 4)
    private BigDecimal marginValue;

    @Column(name = "margin_percent", precision = 12, scale = 2)
    private BigDecimal marginPercent;

    protected DocumentLine() {
    }

    DocumentLine(Document document, DocumentLineRow row) {
        this.document = document;
        this.lineNumber = row.lineNumber();
        this.itm8 = row.itm8();
        apply(row);
    }

    /** Linię w dokumencie wyznacza numer razem z ITM8 (zwroty prasy mają wszystkie linie z numerem 1). */
    public String lineKey() {
        return lineNumber + "/" + itm8;
    }

    /** Pozycja-znacznik gratisu z systemu sklepu, np. "GRATIS SPOŻYWKA MIX". */
    boolean isGratisItem() {
        return name != null && name.toUpperCase(Locale.ROOT).contains("GRATIS");
    }

    /** Wartości z pliku; {@code true}, gdy któraś się zmieniła. */
    boolean apply(DocumentLineRow row) {
        boolean changed = !Objects.equals(supplierCode, row.supplierCode())
                || !Objects.equals(supplierName, row.supplierName())
                || !Objects.equals(nomenclatureCode, row.nomenclatureCode())
                || !Objects.equals(nomenclatureName, row.nomenclatureName())
                || !Objects.equals(ean, row.ean())
                || !Objects.equals(name, row.name())
                || !Objects.equals(brand, row.brand())
                || !Objects.equals(unit, row.unit())
                || !sameNumber(quantity, row.quantity())
                || !sameNumber(purchasePrice, row.purchasePrice())
                || !sameNumber(salePriceCurrent, row.salePriceCurrent())
                || !sameNumber(salePriceDocument, row.salePriceDocument())
                || !sameNumber(purchaseValue, row.purchaseValue())
                || !sameNumber(saleValueGross, row.saleValueGross())
                || !sameNumber(saleValueNet, row.saleValueNet())
                || !Objects.equals(vatRate, row.vatRate())
                || !sameNumber(vatValue, row.vatValue())
                || !sameNumber(marginValue, row.marginValue())
                || !sameNumber(marginPercent, row.marginPercent());
        supplierCode = row.supplierCode();
        supplierName = row.supplierName();
        nomenclatureCode = row.nomenclatureCode();
        nomenclatureName = row.nomenclatureName();
        ean = row.ean();
        name = row.name();
        brand = row.brand();
        unit = row.unit();
        quantity = row.quantity();
        purchasePrice = row.purchasePrice();
        salePriceCurrent = row.salePriceCurrent();
        salePriceDocument = row.salePriceDocument();
        purchaseValue = row.purchaseValue();
        saleValueGross = row.saleValueGross();
        saleValueNet = row.saleValueNet();
        vatRate = row.vatRate();
        vatValue = row.vatValue();
        marginValue = row.marginValue();
        marginPercent = row.marginPercent();
        return changed;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    public UUID getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getItm8() {
        return itm8;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getNomenclatureCode() {
        return nomenclatureCode;
    }

    public String getNomenclatureName() {
        return nomenclatureName;
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

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getSalePriceCurrent() {
        return salePriceCurrent;
    }

    public BigDecimal getSalePriceDocument() {
        return salePriceDocument;
    }

    public BigDecimal getPurchaseValue() {
        return purchaseValue;
    }

    public BigDecimal getSaleValueGross() {
        return saleValueGross;
    }

    public BigDecimal getSaleValueNet() {
        return saleValueNet;
    }

    public Short getVatRate() {
        return vatRate;
    }

    public BigDecimal getVatValue() {
        return vatValue;
    }

    public BigDecimal getMarginValue() {
        return marginValue;
    }

    public BigDecimal getMarginPercent() {
        return marginPercent;
    }
}
