package com.intermanage.backend.mea.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.mea.component.MeaLeafletParser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Pozycja opisu gazetki. {@code promoItm8} to ITM 8 PROMO (8 cyfr bez zer wiodących), ten sam co {@code meaItm8}
 * pozycji formularza MEA; {@code promoForm} to oznaczenia z FORMA PROMOCJI, każde w osobnej linii.
 */
@Entity
@Table(name = "mea_leaflet_lines")
public class MeaLeafletLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false, updatable = false)
    private MeaLeafletImport leafletImport;

    @Column(nullable = false)
    private int lp;

    private String section;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(name = "mea_number")
    private String meaNumber;

    private String itm8;

    @Column(name = "promo_itm8")
    private String promoItm8;

    @Column(nullable = false)
    private String name;

    private String brand;

    @Column(name = "promo_form")
    private String promoForm;

    @Column(name = "mega_kind")
    private String megaKind;

    @Column(name = "mega_variant")
    private String megaVariant;

    @Column(name = "mega_quantity")
    private Integer megaQuantity;

    @Column(name = "mega_price", precision = 12, scale = 2)
    private BigDecimal megaPrice;

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "client_price", precision = 12, scale = 2)
    private BigDecimal clientPrice;

    @Column(name = "client_limit")
    private Integer clientLimit;

    @Column(name = "delivery_form")
    private String deliveryForm;

    protected MeaLeafletLine() {
    }

    public MeaLeafletLine(MeaLeafletParser.Line line) {
        this.lp = line.lp();
        this.section = line.section();
        this.dateFrom = line.dateFrom();
        this.dateTo = line.dateTo();
        this.meaNumber = line.meaNumber();
        this.itm8 = line.itm8();
        this.promoItm8 = line.promoItm8();
        this.name = line.name();
        this.brand = line.brand();
        this.promoForm = line.promoForm().isEmpty() ? null : String.join("\n", line.promoForm());
        this.megaKind = line.megaKind();
        this.megaVariant = line.megaVariant();
        this.megaQuantity = line.megaQuantity();
        this.megaPrice = line.megaPrice();
        this.purchasePrice = line.purchasePrice();
        this.clientPrice = line.clientPrice();
        this.clientLimit = line.clientLimit();
        this.deliveryForm = line.deliveryForm();
    }

    void attach(MeaLeafletImport leafletImport) {
        this.leafletImport = leafletImport;
    }

    public MeaLeafletImport getLeafletImport() {
        return leafletImport;
    }

    public int getLp() {
        return lp;
    }

    public String getSection() {
        return section;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public String getMeaNumber() {
        return meaNumber;
    }

    public String getItm8() {
        return itm8;
    }

    public String getPromoItm8() {
        return promoItm8;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getPromoForm() {
        return promoForm;
    }

    public String getMegaKind() {
        return megaKind;
    }

    public String getMegaVariant() {
        return megaVariant;
    }

    public Integer getMegaQuantity() {
        return megaQuantity;
    }

    public BigDecimal getMegaPrice() {
        return megaPrice;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getClientPrice() {
        return clientPrice;
    }

    public Integer getClientLimit() {
        return clientLimit;
    }

    public String getDeliveryForm() {
        return deliveryForm;
    }
}
