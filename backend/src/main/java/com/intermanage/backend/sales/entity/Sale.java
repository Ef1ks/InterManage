package com.intermanage.backend.sales.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Sprzedaż jednego artykułu (ITM8) pod jednym EAN-em w jednym dniu, do odczytu w raportach. Zapisuje ją wyłącznie import paczkami
 * ({@link com.intermanage.backend.sales.repository.SaleRepository#insertNew}). Raz zapisana jest jedynym źródłem prawdy:
 * nie zmienia się ani nie znika (pilnuje tego też wyzwalacz w bazie).
 * Znaczenie kolumn opisuje migracja V24__nomenclatures_and_sales.sql.
 */
@Entity
@Immutable
@Table(name = "sales")
public class Sale {

    @Embeddable
    public record Key(
            @Column(name = "store_id") UUID storeId,
            @Column(name = "sale_date") LocalDate saleDate,
            @Column(length = 13) String itm8,
            /** EAN Prio; ten sam ITM8 sprzedaje się w jednym dniu pod kilkoma EAN-ami. Bywa pusty. */
            @Column(length = 14) String ean) {
    }

    @EmbeddedId
    private Key id;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "cost_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal costValue;

    @Column(name = "sale_value_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal saleValueGross;

    @Column(name = "vat_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatValue;

    /** Brutto - VAT, liczone przez bazę. */
    @Column(name = "sale_value_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal saleValueNet;

    @Column(name = "margin_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal marginValue;

    @Column(name = "margin_percent", precision = 9, scale = 2)
    private BigDecimal marginPercent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false)
    private SalesImport salesImport;

    protected Sale() {
    }

    public Key getId() {
        return id;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getCostValue() {
        return costValue;
    }

    public BigDecimal getSaleValueGross() {
        return saleValueGross;
    }

    public BigDecimal getVatValue() {
        return vatValue;
    }

    public BigDecimal getSaleValueNet() {
        return saleValueNet;
    }

    public BigDecimal getMarginValue() {
        return marginValue;
    }

    public BigDecimal getMarginPercent() {
        return marginPercent;
    }

    public SalesImport getSalesImport() {
        return salesImport;
    }
}
