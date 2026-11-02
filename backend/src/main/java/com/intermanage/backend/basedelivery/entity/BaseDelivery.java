package com.intermanage.backend.basedelivery.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

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

/**
 * Dokument dostawy z bazy: jeden bon z pliku .xls (opis w V71__base_deliveries.sql). Ponowne wgranie bonu daje
 * poprzedniemu wpisowi znacznik {@code replacedAt}.
 */
@Entity
@Table(name = "base_deliveries")
public class BaseDelivery {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Column(name = "delivery_date", nullable = false, updatable = false)
    private LocalDate deliveryDate;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", updatable = false)
    private AppUser uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private AppUser replacedBy;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<BaseDeliveryLine> lines = new ArrayList<>();

    protected BaseDelivery() {
    }

    public BaseDelivery(Store store, String number, LocalDate deliveryDate, String fileName, AppUser by) {
        this.store = store;
        this.number = number;
        this.deliveryDate = deliveryDate;
        this.fileName = fileName;
        this.uploadedBy = by;
    }

    @PrePersist
    void onCreate() {
        uploadedAt = Instant.now();
    }

    public void addLine(BaseDeliveryLine line) {
        lines.add(line);
        lineCount = lines.size();
    }

    public void replace(AppUser by) {
        replacedAt = Instant.now();
        replacedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getNumber() {
        return number;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineCount() {
        return lineCount;
    }

    public AppUser getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public List<BaseDeliveryLine> getLines() {
        return lines;
    }
}
