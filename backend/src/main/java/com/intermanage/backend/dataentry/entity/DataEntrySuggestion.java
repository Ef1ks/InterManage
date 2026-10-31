package com.intermanage.backend.dataentry.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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
 * Propozycja dla nowego artykułu z "Uzupełnij z AI": wskazania warstwy bez modelu, odpowiedź modelu, co trafiło do
 * tabeli i co użytkownik wyeksportował. Służy do mierzenia trafności; wpisy nie są usuwane.
 */
@Entity
@Table(name = "data_entry_suggestions")
public class DataEntrySuggestion {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "ksef_invoice_id", updatable = false)
    private UUID ksefInvoiceId;

    @Column(length = 14, updatable = false)
    private String ean;

    @Column(name = "invoice_name", nullable = false, updatable = false)
    private String invoiceName;

    @Column(name = "invoice_unit", length = 20, updatable = false)
    private String invoiceUnit;

    @Column(name = "names_subfamily", length = 6, updatable = false)
    private String namesSubfamily;

    @Column(name = "producer_subfamily", length = 6, updatable = false)
    private String producerSubfamily;

    @Column(name = "producer_products", updatable = false)
    private Integer producerProducts;

    @Column(name = "producer_share", precision = 5, scale = 4, updatable = false)
    private BigDecimal producerShare;

    @Column(nullable = false, updatable = false)
    private boolean certain;

    @Column(length = 60, updatable = false)
    private String model;

    @Column(name = "model_ok", nullable = false, updatable = false)
    private boolean modelOk;

    @Column(name = "model_name", updatable = false)
    private String modelName;

    @Column(name = "model_brand", updatable = false)
    private String modelBrand;

    @Column(name = "model_subfamily", length = 6, updatable = false)
    private String modelSubfamily;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "model_alternatives", columnDefinition = "text[]", updatable = false)
    private List<String> modelAlternatives;

    @Column(name = "model_confidence", precision = 4, scale = 3, updatable = false)
    private BigDecimal modelConfidence;

    @Column(name = "model_unit", length = 4, updatable = false)
    private String modelUnit;

    @Column(name = "model_net_content", precision = 12, scale = 3, updatable = false)
    private BigDecimal modelNetContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SuggestionStatus status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]", updatable = false)
    private List<String> proposals;

    @Column(updatable = false)
    private String name;

    @Column(updatable = false)
    private String brand;

    @Column(length = 6, updatable = false)
    private String subfamily;

    @Column(length = 4, updatable = false)
    private String unit;

    @Column(name = "net_content", precision = 12, scale = 3, updatable = false)
    private BigDecimal netContent;

    @Column(name = "final_name")
    private String finalName;

    @Column(name = "final_brand")
    private String finalBrand;

    @Column(name = "final_subfamily", length = 6)
    private String finalSubfamily;

    @Column(name = "final_unit", length = 4)
    private String finalUnit;

    @Column(name = "final_net_content", precision = 12, scale = 3)
    private BigDecimal finalNetContent;

    @Column(name = "exported_at")
    private Instant exportedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exported_by")
    private AppUser exportedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DataEntrySuggestion() {
    }

    /** Wskazania warstwy bez modelu dla jednej pozycji faktury. */
    public record Evidence(String namesSubfamily, String producerSubfamily, Integer producerProducts,
            BigDecimal producerShare, boolean certain) {
    }

    /** Odpowiedź modelu; {@code ok = false}, gdy model nie odpowiedział. */
    public record ModelAnswer(String model, boolean ok, String name, String brand, String subfamily,
            List<String> alternatives, BigDecimal confidence, String unit, BigDecimal netContent) {
    }

    /** Co trafiło do tabeli. */
    public record Proposal(SuggestionStatus status, List<String> subfamilies, String name, String brand,
            String subfamily, String unit, BigDecimal netContent) {
    }

    public DataEntrySuggestion(Store store, UUID ksefInvoiceId, String ean, String invoiceName, String invoiceUnit,
            Evidence evidence, ModelAnswer answer, Proposal proposal, AppUser createdBy) {
        this.store = store;
        this.ksefInvoiceId = ksefInvoiceId;
        this.ean = ean;
        this.invoiceName = invoiceName;
        this.invoiceUnit = invoiceUnit;
        this.namesSubfamily = evidence.namesSubfamily();
        this.producerSubfamily = evidence.producerSubfamily();
        this.producerProducts = evidence.producerProducts();
        this.producerShare = evidence.producerShare();
        this.certain = evidence.certain();
        this.model = answer.model();
        this.modelOk = answer.ok();
        this.modelName = answer.name();
        this.modelBrand = answer.brand();
        this.modelSubfamily = answer.subfamily();
        this.modelAlternatives = answer.alternatives();
        this.modelConfidence = answer.confidence();
        this.modelUnit = answer.unit();
        this.modelNetContent = answer.netContent();
        this.status = proposal.status();
        this.proposals = proposal.subfamilies();
        this.name = proposal.name();
        this.brand = proposal.brand();
        this.subfamily = proposal.subfamily();
        this.unit = proposal.unit();
        this.netContent = proposal.netContent();
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Wartości z tabeli przy eksporcie do Excela; kolejny eksport je nadpisuje. */
    public void exported(String name, String brand, String subfamily, String unit, BigDecimal netContent,
            AppUser by) {
        this.finalName = name;
        this.finalBrand = brand;
        this.finalSubfamily = subfamily;
        this.finalUnit = unit;
        this.finalNetContent = netContent;
        this.exportedBy = by;
        this.exportedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }
}
