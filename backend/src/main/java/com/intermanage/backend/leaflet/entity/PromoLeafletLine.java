package com.intermanage.backend.leaflet.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.leaflet.dto.LeafletRow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Pozycja gazetki tak, jak przyszła w pliku (opis kolumn w V75__promo_leaflets.sql). */
@Entity
@Table(name = "promo_leaflet_lines")
public class PromoLeafletLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leaflet_id", nullable = false, updatable = false)
    private PromoLeaflet leaflet;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(length = 200, updatable = false)
    private String section;

    @Column(name = "date_from", nullable = false, updatable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false, updatable = false)
    private LocalDate dateTo;

    @Column(name = "mea_code", length = 20, updatable = false)
    private String meaCode;

    @Column(length = 13, updatable = false)
    private String itm8;

    @Column(name = "promo_itm8", length = 13, updatable = false)
    private String promoItm8;

    @Column(nullable = false, length = 20, updatable = false)
    private String ean;

    @Column(nullable = false, length = 500, updatable = false)
    private String name;

    @Column(length = 200, updatable = false)
    private String brand;

    @Column(name = "promo_form", updatable = false)
    private String promoForm;

    @Column(name = "mega_kind", length = 100, updatable = false)
    private String megaKind;

    @Column(name = "client_price", length = 50, updatable = false)
    private String clientPrice;

    @Column(precision = 12, scale = 4, updatable = false)
    private BigDecimal price;

    @Column(name = "shelf_label", length = 300, updatable = false)
    private String shelfLabel;

    protected PromoLeafletLine() {
    }

    public PromoLeafletLine(PromoLeaflet leaflet, LeafletRow row) {
        this.leaflet = leaflet;
        this.lineNo = row.lineNo();
        this.section = row.section();
        this.dateFrom = row.dateFrom();
        this.dateTo = row.dateTo();
        this.meaCode = row.meaCode();
        this.itm8 = row.itm8();
        this.promoItm8 = row.promoItm8();
        this.ean = row.ean();
        this.name = row.name();
        this.brand = row.brand();
        this.promoForm = row.promoForm();
        this.megaKind = row.megaKind();
        this.clientPrice = row.clientPrice();
        this.price = row.price();
        this.shelfLabel = row.shelfLabel();
    }

    public UUID getId() {
        return id;
    }

    public PromoLeaflet getLeaflet() {
        return leaflet;
    }

    public int getLineNo() {
        return lineNo;
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

    public String getMeaCode() {
        return meaCode;
    }

    public String getItm8() {
        return itm8;
    }

    public String getPromoItm8() {
        return promoItm8;
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

    public String getPromoForm() {
        return promoForm;
    }

    public String getMegaKind() {
        return megaKind;
    }

    public String getClientPrice() {
        return clientPrice;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getShelfLabel() {
        return shelfLabel;
    }
}
