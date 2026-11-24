package com.intermanage.backend.cadtop.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Plik TOP rejonu pobrany z portalu (V186); artykuły ({@code cad_top_articles}) są bez encji. Nowszy plik tego samego
 * numeru cadencier daje poprzedniemu znacznik {@code replacedAt}.
 */
@Entity
@Table(name = "cad_top_files")
public class CadTopFile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String region;

    @Column(name = "nr_cad", nullable = false, updatable = false)
    private String nrCad;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "source_path", nullable = false, updatable = false)
    private String sourcePath;

    /** Data zmiany w portalu, tak jak ją podał ({@code 2026-11-24 09:29:26}): porównuje się jak tekst. */
    @Column(name = "source_updated_at", nullable = false, updatable = false)
    private String sourceUpdatedAt;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(name = "article_count", nullable = false, updatable = false)
    private int articleCount;

    @Column(name = "downloaded_at", nullable = false, updatable = false)
    private Instant downloadedAt;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    protected CadTopFile() {
    }

    public CadTopFile(String region, String nrCad, String fileName, String sourcePath, String sourceUpdatedAt,
            String sha256, int articleCount) {
        this.region = region;
        this.nrCad = nrCad;
        this.fileName = fileName.length() <= 300 ? fileName : fileName.substring(0, 300);
        this.sourcePath = sourcePath;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.sha256 = sha256;
        this.articleCount = articleCount;
        this.downloadedAt = Instant.now();
    }

    public void replace() {
        replacedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getNrCad() {
        return nrCad;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public int getArticleCount() {
        return articleCount;
    }

    public Instant getDownloadedAt() {
        return downloadedAt;
    }
}
