package com.intermanage.backend.dataentry.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Wydany plik nowych artykułów: dostawca i stawki VAT dla makra, które go wczyta (opis w V191). */
@Entity
@Table(name = "product_file_exports")
public class ProductFileExport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "content_sha256", nullable = false, updatable = false, length = 64)
    private String contentSha256;

    @Column(name = "supplier_code", updatable = false, length = 20)
    private String supplierCode;

    /** {@code EAN;procent VAT}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, updatable = false, columnDefinition = "text[]")
    private List<String> articles;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductFileExport() {
    }

    public ProductFileExport(UUID storeId, String fileName, String contentSha256, String supplierCode,
            List<String> articles) {
        this.storeId = storeId;
        this.fileName = fileName;
        this.contentSha256 = contentSha256;
        this.supplierCode = supplierCode;
        this.articles = List.copyOf(articles);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public List<String> getArticles() {
        return articles;
    }
}
