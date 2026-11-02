package com.intermanage.backend.basedelivery.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.basedelivery.dto.BaseDeliveryRow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Linia dokumentu dostawy z bazy tak, jak przyszła w pliku (opis kolumn w V71__base_deliveries.sql). */
@Entity
@Table(name = "base_delivery_lines")
public class BaseDeliveryLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, updatable = false)
    private BaseDelivery delivery;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(nullable = false, length = 20, updatable = false)
    private String carrier;

    @Column(name = "order_number", length = 20, updatable = false)
    private String orderNumber;

    @Column(nullable = false, length = 13, updatable = false)
    private String itm8;

    @Column(nullable = false, length = 200, updatable = false)
    private String name;

    @Column(length = 100, updatable = false)
    private String brand;

    @Column(nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal pack;

    @Column(length = 3, updatable = false)
    private String deposit;

    @Column(name = "best_before", updatable = false)
    private LocalDate bestBefore;

    @Column(name = "days_for_store", updatable = false)
    private Integer daysForStore;

    @Column(length = 14, updatable = false)
    private String ean;

    @Column(length = 20, updatable = false)
    private String pkwiu;

    @Column(name = "supplier_code", nullable = false, length = 10, updatable = false)
    private String supplierCode;

    @Column(name = "mea_code", length = 20, updatable = false)
    private String meaCode;

    @Column(name = "coli_ordered", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal coliOrdered;

    @Column(name = "coli_prepared", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal coliPrepared;

    @Column(name = "purchase_price", nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal purchasePrice;

    @Column(name = "retail_price", precision = 12, scale = 2, updatable = false)
    private BigDecimal retailPrice;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal vatRate;

    @Column(name = "vat_value", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal vatValue;

    @Column(name = "net_value", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal netValue;

    @Column(name = "gross_value", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal grossValue;

    @Column(name = "region_code", length = 2, updatable = false)
    private String regionCode;

    @Column(name = "family_code", length = 2, updatable = false)
    private String familyCode;

    @Column(name = "subfamily_code", length = 2, updatable = false)
    private String subfamilyCode;

    @Column(name = "sale_content", precision = 12, scale = 3, updatable = false)
    private BigDecimal saleContent;

    @Column(name = "sale_unit", length = 2, updatable = false)
    private String saleUnit;

    @Column(length = 10, updatable = false)
    private String cn;

    @Column(length = 10, updatable = false)
    private String gtu;

    protected BaseDeliveryLine() {
    }

    public BaseDeliveryLine(BaseDelivery delivery, int lineNo, BaseDeliveryRow row) {
        this.delivery = delivery;
        this.lineNo = lineNo;
        this.carrier = row.carrier();
        this.orderNumber = row.orderNumber();
        this.itm8 = row.itm8();
        this.name = row.name();
        this.brand = row.brand();
        this.pack = row.pack();
        this.deposit = row.deposit();
        this.bestBefore = row.bestBefore();
        this.daysForStore = row.daysForStore();
        this.ean = row.ean();
        this.pkwiu = row.pkwiu();
        this.supplierCode = row.supplierCode();
        this.meaCode = row.meaCode();
        this.coliOrdered = row.coliOrdered();
        this.coliPrepared = row.coliPrepared();
        this.purchasePrice = row.purchasePrice();
        this.retailPrice = row.retailPrice();
        this.vatRate = row.vatRate();
        this.vatValue = row.vatValue();
        this.netValue = row.netValue();
        this.grossValue = row.grossValue();
        this.regionCode = row.regionCode();
        this.familyCode = row.familyCode();
        this.subfamilyCode = row.subfamilyCode();
        this.saleContent = row.saleContent();
        this.saleUnit = row.saleUnit();
        this.cn = row.cn();
        this.gtu = row.gtu();
    }

    /** Ilość przyjęta w sztukach: przygotowane kartony × sztuk w kartonie. */
    public BigDecimal getQuantity() {
        return coliPrepared.multiply(pack);
    }

    /**
     * Towar przyjechał z krótszym terminem, niż baza ma zostawić sklepowi: termin przed dniem dostawy + dni dla
     * sklepu. Bez terminu albo dni (0000-00-00, 0) i bez przyjętej ilości nie liczymy.
     */
    public boolean isShortDated() {
        return bestBefore != null && daysForStore != null && daysForStore > 0
                && coliPrepared.signum() > 0 && bestBefore.isBefore(getRequiredBestBefore());
    }

    /** Najwcześniejszy termin, z którym towar może przyjechać. */
    public LocalDate getRequiredBestBefore() {
        return daysForStore == null ? null : delivery.getDeliveryDate().plusDays(daysForStore);
    }

    public UUID getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getItm8() {
        return itm8;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public BigDecimal getPack() {
        return pack;
    }

    public String getDeposit() {
        return deposit;
    }

    public LocalDate getBestBefore() {
        return bestBefore;
    }

    public Integer getDaysForStore() {
        return daysForStore;
    }

    public String getEan() {
        return ean;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getMeaCode() {
        return meaCode;
    }

    public BigDecimal getColiOrdered() {
        return coliOrdered;
    }

    public BigDecimal getColiPrepared() {
        return coliPrepared;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getRetailPrice() {
        return retailPrice;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public BigDecimal getNetValue() {
        return netValue;
    }

    public BigDecimal getGrossValue() {
        return grossValue;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public String getFamilyCode() {
        return familyCode;
    }

    public String getSubfamilyCode() {
        return subfamilyCode;
    }

    public BigDecimal getSaleContent() {
        return saleContent;
    }

    public String getSaleUnit() {
        return saleUnit;
    }

    public String getCn() {
        return cn;
    }
}
