package com.intermanage.backend.ksef.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.ksef.dto.KsefInvoiceMetadata;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Faktura zakupowa firmy z KSeF (firma jest nabywcą), w stanie z ostatniego pobrania. Kluczem jest NIP firmy (kontekst
 * połączenia) + numer KSeF: faktura jest wspólna dla sklepów firmy. {@link #getInternalIds()} to IDWew z Podmiotów 3 -
 * po nich sklep rozpoznaje swoje faktury. "Wprowadzona" jest per sklep ({@link KsefInvoiceEntry} albo numer w PZ).
 */
@Entity
@Table(name = "ksef_invoices")
public class KsefInvoice {

    public static final String INTERNAL_ID_TYPE = "InternalId";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** NIP firmy, której połączenie pobrało fakturę (nabywca). */
    @Column(nullable = false, length = 10, updatable = false)
    private String nip;

    @Column(name = "ksef_number", nullable = false, length = 36, updatable = false)
    private String ksefNumber;

    @Column(name = "invoice_number", nullable = false, length = 256)
    private String invoiceNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "invoicing_date", nullable = false)
    private Instant invoicingDate;

    @Column(name = "permanent_storage_date", nullable = false)
    private Instant permanentStorageDate;

    @Column(name = "seller_nip", nullable = false, length = 10)
    private String sellerNip;

    @Column(name = "seller_name", length = 512)
    private String sellerName;

    @Column(name = "buyer_identifier", length = 50)
    private String buyerIdentifier;

    @Column(name = "buyer_name", length = 512)
    private String buyerName;

    @Column(name = "net_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "gross_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "vat_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal vatAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Rodzaj z KSeF: Vat, Kor, Zal, Roz, Upr, KorZal, KorRoz... */
    @Column(name = "invoice_type", nullable = false, length = 12)
    private String invoiceType;

    @Column(name = "has_attachment", nullable = false)
    private boolean hasAttachment;

    @ElementCollection
    @CollectionTable(name = "ksef_invoice_internal_ids", joinColumns = @JoinColumn(name = "invoice_id"))
    private Set<InternalIdRef> internalIds = new HashSet<>();

    /** Przy korekcie: faktury, które koryguje (z XML), odczytane w tle ({@link #getCorrectedReadAt()}). */
    @ElementCollection
    @CollectionTable(name = "ksef_invoice_corrected", joinColumns = @JoinColumn(name = "invoice_id"))
    private Set<CorrectedRef> correctedInvoices = new HashSet<>();

    @Column(name = "corrected_read_at")
    private Instant correctedReadAt;

    @Column(name = "corrected_error", length = 500)
    private String correctedError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** IDWew z Podmiotu 3 faktury i nazwa, pod jaką występuje. */
    @Embeddable
    public record InternalIdRef(
            @Column(name = "internal_id", nullable = false, length = 16) String internalId,
            @Column(length = 512) String name) {
    }

    /** Faktura korygowana: numer, data wystawienia i numer KSeF (brak przy fakturze spoza KSeF). */
    @Embeddable
    public record CorrectedRef(
            @Column(name = "invoice_number", length = 256) String invoiceNumber,
            @Column(name = "ksef_number", length = 36) String ksefNumber,
            @Column(name = "issue_date") LocalDate issueDate) {
    }

    protected KsefInvoice() {
    }

    public KsefInvoice(String nip, KsefInvoiceMetadata metadata) {
        this.nip = nip;
        this.ksefNumber = metadata.ksefNumber();
        apply(metadata);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Dane z kolejnego pobrania (faktura w KSeF się nie zmienia, ale metadane mogą się uzupełnić). */
    public void update(KsefInvoiceMetadata metadata) {
        apply(metadata);
        updatedAt = Instant.now();
    }

    private void apply(KsefInvoiceMetadata metadata) {
        invoiceNumber = metadata.invoiceNumber();
        issueDate = metadata.issueDate();
        invoicingDate = metadata.invoicingDate().toInstant();
        permanentStorageDate = metadata.permanentStorageDate().toInstant();
        sellerNip = metadata.seller().nip();
        sellerName = metadata.seller().name();
        KsefInvoiceMetadata.Identifier buyer = metadata.buyer() != null ? metadata.buyer().identifier() : null;
        buyerIdentifier = buyer != null ? buyer.value() : null;
        buyerName = metadata.buyer() != null ? metadata.buyer().name() : null;
        netAmount = metadata.netAmount();
        grossAmount = metadata.grossAmount();
        vatAmount = metadata.vatAmount();
        currency = metadata.currency();
        invoiceType = metadata.invoiceType();
        hasAttachment = metadata.hasAttachment();
        Set<InternalIdRef> fresh = internalIdsOf(metadata.thirdSubjects());
        if (!fresh.equals(internalIds)) {
            internalIds.clear();
            internalIds.addAll(fresh);
        }
    }

    private static Set<InternalIdRef> internalIdsOf(List<KsefInvoiceMetadata.ThirdSubject> subjects) {
        if (subjects == null) {
            return Set.of();
        }
        return subjects.stream()
                .filter(subject -> subject.identifier() != null
                        && INTERNAL_ID_TYPE.equals(subject.identifier().type())
                        && subject.identifier().value() != null)
                // Ten sam IDWew bywa w kilku rolach naraz - liczy się raz, z pierwszą nazwą.
                .collect(Collectors.toMap(subject -> subject.identifier().value(),
                        subject -> new InternalIdRef(subject.identifier().value(), subject.name()),
                        (first, second) -> first))
                .values().stream().collect(Collectors.toSet());
    }

    /** Faktury korygowane odczytane z XML korekty; {@code error}: XML nie dał się pobrać albo odczytać. */
    public void recordCorrected(Set<CorrectedRef> refs, String error) {
        correctedInvoices.clear();
        correctedInvoices.addAll(refs);
        correctedReadAt = Instant.now();
        correctedError = error;
    }

    public boolean isCorrection() {
        return invoiceType != null && invoiceType.startsWith("Kor");
    }

    public UUID getId() {
        return id;
    }

    public String getNip() {
        return nip;
    }

    public String getKsefNumber() {
        return ksefNumber;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public Instant getInvoicingDate() {
        return invoicingDate;
    }

    public Instant getPermanentStorageDate() {
        return permanentStorageDate;
    }

    public String getSellerNip() {
        return sellerNip;
    }

    public String getSellerName() {
        return sellerName;
    }

    public String getBuyerIdentifier() {
        return buyerIdentifier;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getVatAmount() {
        return vatAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getInvoiceType() {
        return invoiceType;
    }

    public boolean isHasAttachment() {
        return hasAttachment;
    }

    public Set<InternalIdRef> getInternalIds() {
        return internalIds;
    }

    public Set<CorrectedRef> getCorrectedInvoices() {
        return correctedInvoices;
    }

    public Instant getCorrectedReadAt() {
        return correctedReadAt;
    }

    public String getCorrectedError() {
        return correctedError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
