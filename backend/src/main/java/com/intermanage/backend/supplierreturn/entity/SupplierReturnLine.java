package com.intermanage.backend.supplierreturn.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Artykuł zwrotu z ilością z chwili utworzenia listy. */
@Entity
@Table(name = "supplier_return_lines")
public class SupplierReturnLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "return_id", nullable = false, updatable = false)
    private UUID returnId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(updatable = false)
    private String itm8;

    @Column(updatable = false)
    private String ean;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private String brand;

    @Column(nullable = false, updatable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    protected SupplierReturnLine() {
    }

    public SupplierReturnLine(UUID returnId, int position, String itm8, String ean, String name, String brand,
            BigDecimal quantity) {
        this.returnId = returnId;
        this.position = position;
        this.itm8 = itm8;
        this.ean = ean;
        this.name = name;
        this.brand = brand;
        this.quantity = quantity;
    }

    public UUID getReturnId() {
        return returnId;
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

    public BigDecimal getQuantity() {
        return quantity;
    }
}
