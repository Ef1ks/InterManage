package com.intermanage.backend.cadtop.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

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
import jakarta.persistence.Table;

/** Zlecone ustawienie ITM8 z cadencier jako priorytetowego (V188); cofnięcie to znacznik. */
@Entity
@Table(name = "cad_priority_runs")
public class CadPriorityRun {

    /** Nazwy trafiają do bazy. */
    public enum Source {
        MACRO, MANUAL
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "top_itm8", nullable = false, updatable = false)
    private String topItm8;

    @Column(nullable = false, updatable = false)
    private String itm8;

    @Column(updatable = false)
    private String ean;

    @Column(name = "nr_cad", nullable = false, updatable = false)
    private String nrCad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

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

    protected CadPriorityRun() {
    }

    public CadPriorityRun(Store store, String topItm8, String itm8, String ean, String nrCad, Source source,
            AppUser by) {
        this.store = store;
        this.topItm8 = topItm8;
        this.itm8 = itm8;
        this.ean = ean;
        this.nrCad = nrCad;
        this.source = source;
        this.createdBy = by;
        this.createdAt = Instant.now();
    }

    public void delete(AppUser by) {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            deletedBy = by;
        }
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public String getTopItm8() {
        return topItm8;
    }

    public String getItm8() {
        return itm8;
    }

    public Source getSource() {
        return source;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
