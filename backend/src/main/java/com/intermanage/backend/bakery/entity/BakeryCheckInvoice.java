package com.intermanage.backend.bakery.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.ksef.entity.KsefInvoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Faktura przypięta do weryfikacji; odpięcie to znacznik, kafelki WZ zostają. */
@Entity
@Table(name = "bakery_check_invoices")
public class BakeryCheckInvoice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id", nullable = false, updatable = false)
    private BakeryCheck check;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private KsefInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by", updatable = false)
    private AppUser addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected BakeryCheckInvoice() {
    }

    public BakeryCheckInvoice(BakeryCheck check, KsefInvoice invoice, AppUser addedBy) {
        this.check = check;
        this.invoice = invoice;
        this.addedBy = addedBy;
    }

    @PrePersist
    void onCreate() {
        addedAt = Instant.now();
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public BakeryCheck getCheck() {
        return check;
    }

    public KsefInvoice getInvoice() {
        return invoice;
    }
}
