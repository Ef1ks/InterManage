package com.intermanage.backend.product.entity;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Para ITM8 + EAN z multikodów, tylko do odczytu: zapisuje je {@code ProductCodeImportRepository.merge} jednym
 * zapytaniem. Encja służy wyszukiwaniu artykułów po każdym obowiązującym EAN-ie ({@code current}), nie tylko głównym.
 */
@Entity
@Immutable
@Table(name = "product_codes")
public class ProductCode {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "itm8", nullable = false, length = 13)
    private String itm8;

    @Column(name = "ean", nullable = false, length = 14)
    private String ean;

    @Column(name = "current", nullable = false)
    private boolean current;

    protected ProductCode() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getItm8() {
        return itm8;
    }

    public String getEan() {
        return ean;
    }

    public boolean isCurrent() {
        return current;
    }
}
