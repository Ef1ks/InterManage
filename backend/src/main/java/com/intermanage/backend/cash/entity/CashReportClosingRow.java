package com.intermanage.backend.cash.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Dokument w zakończonym raporcie, z wartościami z chwili zamknięcia. */
@Entity
@Table(name = "cash_report_closing_rows")
public class CashReportClosingRow {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closing_id", nullable = false, updatable = false)
    private CashReportClosing closing;

    @Column(nullable = false, updatable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private CashDocument document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 2)
    private CashDocumentKind kind;

    @Column(nullable = false, updatable = false, length = 30)
    private String number;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Operacja z opisem z dokumentu. */
    @Column(nullable = false, updatable = false, length = 610)
    private String content;

    @Column(updatable = false, length = 200)
    private String counterparty;

    @Column(nullable = false, updatable = false)
    private boolean cancelled;

    /** Stan kasy po dokumencie; przy anulowanym pusty. */
    @Column(updatable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    protected CashReportClosingRow() {
    }

    public CashReportClosingRow(CashDocument document, BigDecimal balance) {
        this.document = document;
        this.kind = document.getKind();
        this.number = document.getNumber();
        this.amount = document.getAmount();
        this.content = document.getContent();
        this.counterparty = document.getCounterparty();
        this.cancelled = document.isCancelled();
        this.balance = balance;
    }

    void attachTo(CashReportClosing closing, int position) {
        this.closing = closing;
        this.position = position;
    }

    public CashReportClosing getClosing() {
        return closing;
    }

    public UUID getDocumentId() {
        return document.getId();
    }

    public int getPosition() {
        return position;
    }

    public CashDocumentKind getKind() {
        return kind;
    }

    public String getNumber() {
        return number;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public String getContent() {
        return content;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}
