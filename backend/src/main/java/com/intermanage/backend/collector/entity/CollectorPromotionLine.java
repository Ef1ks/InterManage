package com.intermanage.backend.collector.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Artykuł promocji z kolektora: cena promocji i koniec promocji tego artykułu. */
@Entity
@Immutable
@Table(name = "collector_promotion_lines")
public class CollectorPromotionLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "promotion_id", nullable = false)
    private UUID promotionId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 13)
    private String itm8;

    @Column(nullable = false, length = 14)
    private String ean;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    protected CollectorPromotionLine() {
    }

    public CollectorPromotionLine(UUID promotionId, int position, String itm8, String ean, String name, String brand,
            BigDecimal price, LocalDate dateTo) {
        this.promotionId = promotionId;
        this.position = position;
        this.itm8 = itm8;
        this.ean = ean;
        this.name = name;
        this.brand = brand;
        this.price = price;
        this.dateTo = dateTo;
    }

    public UUID getPromotionId() {
        return promotionId;
    }

    public int getPosition() {
        return position;
    }

    public String getItm8() {
        return itm8;
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

    public BigDecimal getPrice() {
        return price;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }
}
