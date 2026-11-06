package com.intermanage.backend.bakery.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.ksef.entity.KsefInvoice;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Dokument WZ w weryfikacji: kafelek z ilościami (opis w V85__bakery_checks.sql). */
@Entity
@Table(name = "bakery_wz")
public class BakeryWz {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id", nullable = false, updatable = false)
    private BakeryCheck check;

    @Column(nullable = false, updatable = false)
    private int position;

    private String number;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "date_estimated", nullable = false)
    private boolean dateEstimated;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_invoice_id", updatable = false)
    private KsefInvoice sourceInvoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id")
    private BakeryWzScan scan;

    @OneToMany(mappedBy = "wz", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BakeryWzLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected BakeryWz() {
    }

    public BakeryWz(BakeryCheck check, int position, String number, LocalDate deliveryDate, boolean dateEstimated,
            KsefInvoice sourceInvoice, AppUser createdBy) {
        this.check = check;
        this.position = position;
        this.number = number;
        this.deliveryDate = deliveryDate;
        this.dateEstimated = dateEstimated;
        this.sourceInvoice = sourceInvoice;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Data poprawiona przez pracownika przestaje być szacunkiem z okresu faktury. */
    public void describe(String number, LocalDate deliveryDate) {
        if (deliveryDate == null || !deliveryDate.equals(this.deliveryDate)) {
            dateEstimated = false;
        }
        this.number = number;
        this.deliveryDate = deliveryDate;
    }

    public void setScan(BakeryWzScan scan) {
        this.scan = scan;
    }

    public void delete(AppUser user) {
        deletedAt = Instant.now();
        deletedBy = user;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public BakeryCheck getCheck() {
        return check;
    }

    public int getPosition() {
        return position;
    }

    public String getNumber() {
        return number;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public boolean isDateEstimated() {
        return dateEstimated;
    }

    public KsefInvoice getSourceInvoice() {
        return sourceInvoice;
    }

    public BakeryWzScan getScan() {
        return scan;
    }

    public List<BakeryWzLine> getLines() {
        return lines;
    }
}
