package com.intermanage.backend.meat.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.product.entity.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Pozycja WZ: nazwa i kod jak na dokumencie, cena netto za kg i artykuł z kartoteki ({@code null} = sam zakup, bez
 * ceny sprzedaży w raporcie).
 */
@Entity
@Table(name = "meat_delivery_lines")
public class MeatDeliveryLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, updatable = false)
    private MeatDelivery delivery;

    @Column(nullable = false)
    private int position;

    @Column(name = "document_name", nullable = false)
    private String documentName;

    @Column(name = "document_code", length = 50)
    private String documentCode;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "handwritten_correction", nullable = false)
    private boolean handwrittenCorrection;

    private String note;

    protected MeatDeliveryLine() {
    }

    public MeatDeliveryLine(String documentName, String documentCode, BigDecimal unitPrice, Product product,
            boolean handwrittenCorrection, String note) {
        this.documentName = documentName;
        this.documentCode = documentCode;
        this.unitPrice = unitPrice;
        this.product = product;
        this.handwrittenCorrection = handwrittenCorrection;
        this.note = note;
    }

    void attachTo(MeatDelivery delivery, int position) {
        this.delivery = delivery;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getDocumentCode() {
        return documentCode;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Product getProduct() {
        return product;
    }

    public boolean isHandwrittenCorrection() {
        return handwrittenCorrection;
    }

    public String getNote() {
        return note;
    }
}
