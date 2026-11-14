package com.intermanage.backend.cash.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Zakończony raport dzienny: zrzut z chwili zamknięcia (opis w V136__cash_report.sql). */
@Entity
@Table(name = "cash_report_closings")
public class CashReportClosing {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "report_date", nullable = false, updatable = false)
    private LocalDate reportDate;

    @Column(name = "opening_balance", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal openingBalance;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal receipts;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal payments;

    @Column(name = "closing_balance", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal closingBalance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stamp_id", updatable = false)
    private CashStamp stamp;

    /** Pusty = zamknięcie automatyczne o 23:00. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by", updatable = false)
    private AppUser closedBy;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @OneToMany(mappedBy = "closing", cascade = CascadeType.ALL)
    @OrderBy("position")
    private List<CashReportClosingRow> rows = new ArrayList<>();

    protected CashReportClosing() {
    }

    public CashReportClosing(UUID storeId, LocalDate reportDate, BigDecimal openingBalance,
            BigDecimal receipts, BigDecimal payments, BigDecimal closingBalance, CashStamp stamp, AppUser closedBy) {
        this.storeId = storeId;
        this.reportDate = reportDate;
        this.openingBalance = openingBalance;
        this.receipts = receipts;
        this.payments = payments;
        this.closingBalance = closingBalance;
        this.stamp = stamp;
        this.closedBy = closedBy;
    }

    @PrePersist
    void onCreate() {
        closedAt = Instant.now();
    }

    public void addRow(CashReportClosingRow row) {
        row.attachTo(this, rows.size() + 1);
        rows.add(row);
    }

    /** Numer raportu = dzień raportu, np. {@code RK 5/11/2026} (jak w dotychczasowym arkuszu). */
    public String getNumber() {
        return "RK " + reportDate.getDayOfMonth() + "/" + reportDate.getMonthValue() + "/" + reportDate.getYear();
    }

    public int getReceiptCount() {
        return (int) rows.stream().filter(row -> !row.isCancelled() && row.getKind().isReceipt()).count();
    }

    public int getPaymentCount() {
        return (int) rows.stream().filter(row -> !row.isCancelled() && !row.getKind().isReceipt()).count();
    }

    public int getDocumentCount() {
        return rows.size();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public BigDecimal getReceipts() {
        return receipts;
    }

    public BigDecimal getPayments() {
        return payments;
    }

    public BigDecimal getClosingBalance() {
        return closingBalance;
    }

    public CashStamp getStamp() {
        return stamp;
    }

    public AppUser getClosedBy() {
        return closedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public List<CashReportClosingRow> getRows() {
        return rows;
    }
}
