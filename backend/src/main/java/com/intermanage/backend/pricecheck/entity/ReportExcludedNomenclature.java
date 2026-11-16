package com.intermanage.backend.pricecheck.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Nomenklatura (kod rejonu, rodziny albo podrodziny), której artykułów raport weryfikujący zakupy nie pokazuje. Każdy
 * raport ma własną listę, usunięcie z niej to znacznik.
 */
@Entity
@Table(name = "report_excluded_nomenclatures")
public class ReportExcludedNomenclature {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ReviewedReport report;

    @Column(nullable = false, length = 6, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected ReportExcludedNomenclature() {
    }

    public ReportExcludedNomenclature(Store store, ReviewedReport report, String code, AppUser createdBy) {
        this.store = store;
        this.report = report;
        this.code = code;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markRemoved(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public String getCode() {
        return code;
    }
}
