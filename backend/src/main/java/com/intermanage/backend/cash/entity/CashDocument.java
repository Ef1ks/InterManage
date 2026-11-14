package com.intermanage.backend.cash.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

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
 * Dokument kasowy KP albo KW (opis w V136__cash_report.sql). Treść się nie zmienia; pomyłkę anuluje się znacznikiem
 * z powodem, a numer zostaje w historii.
 */
@Entity
@Table(name = "cash_documents")
public class CashDocument {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 2)
    private CashDocumentKind kind;

    @Column(nullable = false, updatable = false)
    private int year;

    @Column(nullable = false, updatable = false)
    private int month;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Operacja z listy „Za co”, np. „Utarg z dnia”. */
    @Column(nullable = false, updatable = false, length = 100)
    private String operation;

    /** Opis do operacji, np. „ZDRAPKOMAT”; opcjonalny. */
    @Column(updatable = false, length = 500)
    private String title;

    @Column(updatable = false, length = 200)
    private String counterparty;

    @Column(name = "counterparty_address", updatable = false, length = 300)
    private String counterpartyAddress;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stamp_id", nullable = false, updatable = false)
    private CashStamp stamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    protected CashDocument() {
    }

    public CashDocument(UUID storeId, CashDocumentKind kind, int sequenceNumber, LocalDate issueDate,
            BigDecimal amount, String operation, String title, String counterparty, String counterpartyAddress,
            CashStamp stamp, AppUser createdBy) {
        this.storeId = storeId;
        this.kind = kind;
        this.year = issueDate.getYear();
        this.month = issueDate.getMonthValue();
        this.operation = operation;
        this.sequenceNumber = sequenceNumber;
        this.issueDate = issueDate;
        this.amount = amount;
        this.counterparty = counterparty;
        this.counterpartyAddress = counterpartyAddress;
        this.title = title;
        this.stamp = stamp;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void cancel(String reason, AppUser by) {
        cancelledAt = Instant.now();
        cancelledBy = by;
        cancelReason = reason;
    }

    public void restore() {
        cancelledAt = null;
        cancelledBy = null;
        cancelReason = null;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    /** Numer na dokumencie i w raporcie, np. {@code KP 22/11/2026}: kolejny w miesiącu, jak w dotychczasowym arkuszu. */
    public String getNumber() {
        return kind.name() + " " + sequenceNumber + "/" + month + "/" + year;
    }

    /** Treść w raporcie: operacja i opis, np. „Utarg z dnia ZDRAPKOMAT”. */
    public String getContent() {
        return title == null ? operation : operation + " " + title;
    }

    /** Wpływ na stan kasy: KP na plus, KW na minus, anulowany zero. */
    public BigDecimal signedAmount() {
        if (isCancelled()) {
            return BigDecimal.ZERO;
        }
        return kind.isReceipt() ? amount : amount.negate();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public CashDocumentKind getKind() {
        return kind;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public String getOperation() {
        return operation;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public String getCounterpartyAddress() {
        return counterpartyAddress;
    }

    public String getTitle() {
        return title;
    }

    public CashStamp getStamp() {
        return stamp;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
