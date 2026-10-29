package com.intermanage.backend.document.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.supplier.entity.Supplier;

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
 * Dokument zarejestrowany jako gratis od dostawcy. Wartością gratisu jest sprzedaż brutto dokumentu, więc nie jest
 * tu kopiowana. Cofnięcie ustawia {@code deletedAt}; zmiana dostawcy, rodzaju albo miesiąca to cofnięcie tego wpisu
 * i nowy wpis. {@code reportMonth} to miesiąc wybrany ręcznie (pierwszy dzień); pusty = miesiąc daty dokumentu.
 */
@Entity
@Table(name = "document_gratis")
public class DocumentGratis {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private GratisKind kind;

    @Column(name = "report_month", updatable = false)
    private LocalDate reportMonth;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by", updatable = false)
    private AppUser registeredBy;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DocumentGratis() {
    }

    public DocumentGratis(Document document, Supplier supplier, GratisKind kind, YearMonth reportMonth,
            AppUser registeredBy) {
        this.document = document;
        this.supplier = supplier;
        this.kind = kind;
        this.reportMonth = reportMonth != null ? reportMonth.atDay(1) : null;
        this.registeredBy = registeredBy;
    }

    @PrePersist
    void onCreate() {
        registeredAt = Instant.now();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public GratisKind getKind() {
        return kind;
    }

    /** Miesiąc wybrany ręcznie; pusty = miesiąc daty dokumentu. */
    public YearMonth getReportMonth() {
        return reportMonth != null ? YearMonth.from(reportMonth) : null;
    }

    /** Miesiąc, do którego gratis liczy się w raporcie. */
    public YearMonth getEffectiveMonth() {
        return reportMonth != null ? YearMonth.from(reportMonth) : YearMonth.from(document.getDocumentDate());
    }

    public AppUser getRegisteredBy() {
        return registeredBy;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
