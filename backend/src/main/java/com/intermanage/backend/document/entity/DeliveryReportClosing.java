package com.intermanage.backend.document.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Zamknięty raport przyjęć PZ za okres: co w nim poszło, zapisuje {@link DeliveryReportClosingDelivery}. Późniejsze
 * zmiany przyjęć z tego okresu trafiają do następnego raportu. Cofnięcie to znacznik.
 */
@Entity
@Table(name = "delivery_report_closings")
public class DeliveryReportClosing {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "period_from", nullable = false, updatable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false, updatable = false)
    private LocalDate periodTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by", updatable = false)
    private AppUser closedBy;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Dostawcy pominięci w raporcie w chwili zamknięcia (opis); {@code null} = żadnych. */
    @Column(name = "excluded_suppliers", updatable = false)
    private String excludedSuppliers;

    protected DeliveryReportClosing() {
    }

    public DeliveryReportClosing(Store store, LocalDate periodFrom, LocalDate periodTo, AppUser closedBy,
            String excludedSuppliers) {
        this.store = store;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.closedBy = closedBy;
        this.closedAt = Instant.now();
        this.excludedSuppliers = excludedSuppliers;
    }

    public void cancel(AppUser by) {
        cancelledBy = by;
        cancelledAt = Instant.now();
    }

    public void restore() {
        cancelledBy = null;
        cancelledAt = null;
    }

    /** Okresy mają wspólny dzień (oba końce włącznie). */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !periodFrom.isAfter(to) && !periodTo.isBefore(from);
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public AppUser getClosedBy() {
        return closedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public AppUser getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getExcludedSuppliers() {
        return excludedSuppliers;
    }
}
