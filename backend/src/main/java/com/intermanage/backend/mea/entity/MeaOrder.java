package com.intermanage.backend.mea.entity;

import java.time.Instant;
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
 * Zamówienie MEA zapisane do ATHOS: treść pliku V… (rekordy po 45 znaków), liczba pozycji z kartonami i kartonów.
 * Wysłane jest dopiero z {@code savedAt}, czyli po udanym zapisie pliku R… w folderze comrnis.
 */
@Entity
@Table(name = "mea_orders")
public class MeaOrder {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(nullable = false, updatable = false)
    private String content;

    @Column(nullable = false, updatable = false)
    private int positions;

    @Column(nullable = false, updatable = false)
    private int colis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "saved_at")
    private Instant savedAt;

    protected MeaOrder() {
    }

    public MeaOrder(Store store, String number, String fileName, String content, int positions, int colis,
            AppUser createdBy) {
        this.store = store;
        this.number = number;
        this.fileName = fileName;
        this.content = content;
        this.positions = positions;
        this.colis = colis;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void saved() {
        if (savedAt == null) {
            savedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public String getFileName() {
        return fileName;
    }

    public int getPositions() {
        return positions;
    }

    public int getColis() {
        return colis;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
