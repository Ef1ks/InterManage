package com.intermanage.backend.collector.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Pozycja listy z kolektora: zeskanowany kod, wpisana ilość i ilość w jednostce głównego kodu. */
@Entity
@Immutable
@Table(name = "collector_list_lines")
public class CollectorListLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "list_id", nullable = false)
    private UUID listId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 13)
    private String itm8;

    @Column(name = "scanned_code", nullable = false, length = 20)
    private String scannedCode;

    @Column(nullable = false, length = 14)
    private String ean;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(name = "entered_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal enteredQuantity;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    protected CollectorListLine() {
    }

    public CollectorListLine(UUID listId, int position, String itm8, String scannedCode, String ean, String name,
            String brand, BigDecimal enteredQuantity, BigDecimal quantity) {
        this.listId = listId;
        this.position = position;
        this.itm8 = itm8;
        this.scannedCode = scannedCode;
        this.ean = ean;
        this.name = name;
        this.brand = brand;
        this.enteredQuantity = enteredQuantity;
        this.quantity = quantity;
    }

    public UUID getListId() {
        return listId;
    }

    public int getPosition() {
        return position;
    }

    public String getItm8() {
        return itm8;
    }

    public String getScannedCode() {
        return scannedCode;
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

    public BigDecimal getEnteredQuantity() {
        return enteredQuantity;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }
}
