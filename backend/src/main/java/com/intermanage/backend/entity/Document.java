package com.intermanage.backend.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.dto.DocumentLineRow;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Dokument magazynowy z raportu Wejścia/Wyjścia (przyjęcie, likwidacja, zwrot...). Kluczem jest typ i numer
 * wewnętrzny dokumentu w sklepie. Kolejne importy aktualizują nagłówek i linie; linii, których zabrakło
 * w nowym pliku, nie usuwamy.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private DocumentType type;

    @Column(nullable = false, length = 30, updatable = false)
    private String number;

    @Column(name = "document_date", nullable = false)
    private LocalDateTime documentDate;

    /** Wspólny dostawca wszystkich linii; {@code null}, gdy linie mają różnych (likwidacje, przeniesienia). */
    @Column(name = "supplier_code", length = 10)
    private String supplierCode;

    @Column(name = "supplier_name", length = 100)
    private String supplierName;

    @Column(name = "supplier_document_number", length = 60)
    private String supplierDocumentNumber;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "purchase_value", nullable = false, precision = 16, scale = 4)
    private BigDecimal purchaseValue = BigDecimal.ZERO;

    @Column(name = "sale_value_gross", nullable = false, precision = 16, scale = 4)
    private BigDecimal saleValueGross = BigDecimal.ZERO;

    /** Któraś linia to pozycja "GRATIS..." - dokument może być gratisem od dostawcy. */
    @Column(name = "has_gratis_line", nullable = false)
    private boolean gratisCandidate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_import_id", nullable = false, updatable = false)
    private DocumentImport firstImport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_import_id", nullable = false)
    private DocumentImport lastImport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL)
    @OrderBy("lineNumber, itm8")
    private List<DocumentLine> lines = new ArrayList<>();

    protected Document() {
    }

    public Document(Store store, DocumentLineRow header, DocumentImport documentImport) {
        this.store = store;
        this.type = header.type();
        this.number = header.documentNumber();
        this.firstImport = documentImport;
        applyHeader(header, documentImport);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Data i numer dokumentu dostawcy z nowego pliku; {@code true}, gdy coś się zmieniło. */
    public boolean applyHeader(DocumentLineRow header, DocumentImport documentImport) {
        lastImport = documentImport;
        boolean changed = !header.documentDate().equals(documentDate)
                || !Objects.equals(header.supplierDocumentNumber(), supplierDocumentNumber);
        documentDate = header.documentDate();
        supplierDocumentNumber = header.supplierDocumentNumber();
        if (changed) {
            updatedAt = Instant.now();
        }
        return changed;
    }

    /** Linia z pliku: nowa albo zaktualizowana. {@code true}, gdy coś się zmieniło. */
    public boolean applyLine(DocumentLineRow row, DocumentLine existing) {
        if (existing == null) {
            lines.add(new DocumentLine(this, row));
            updatedAt = Instant.now();
            return true;
        }
        boolean changed = existing.apply(row);
        if (changed) {
            updatedAt = Instant.now();
        }
        return changed;
    }

    /** Podsumowanie i wspólny dostawca ze wszystkich linii (także tych z wcześniejszych importów). */
    public void recalculateSummary() {
        lineCount = lines.size();
        purchaseValue = lines.stream().map(DocumentLine::getPurchaseValue).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        saleValueGross = lines.stream().map(DocumentLine::getSaleValueGross).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean oneSupplier = lines.stream().map(DocumentLine::getSupplierCode).distinct().count() == 1;
        supplierCode = oneSupplier ? lines.getFirst().getSupplierCode() : null;
        supplierName = oneSupplier ? lines.getFirst().getSupplierName() : null;
        gratisCandidate = lines.stream().anyMatch(DocumentLine::isGratisItem);
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public DocumentType getType() {
        return type;
    }

    public String getNumber() {
        return number;
    }

    public LocalDateTime getDocumentDate() {
        return documentDate;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getSupplierDocumentNumber() {
        return supplierDocumentNumber;
    }

    public int getLineCount() {
        return lineCount;
    }

    public BigDecimal getPurchaseValue() {
        return purchaseValue;
    }

    public BigDecimal getSaleValueGross() {
        return saleValueGross;
    }

    public boolean isGratisCandidate() {
        return gratisCandidate;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<DocumentLine> getLines() {
        return lines;
    }
}
