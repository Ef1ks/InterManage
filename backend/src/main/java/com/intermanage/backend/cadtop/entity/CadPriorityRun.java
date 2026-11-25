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
        /** Makro do schowka albo na komputer sklepu, zlecone przez osobę. */
        MACRO,
        /** Oznaczone jako ustawione ręcznie. */
        MANUAL,
        /** Makro zlecone przez serwer po aktualizacji danych (bez osoby). */
        AGENT
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

    /** Makro na komputerze sklepu, które to ustawia (puste = makro ze schowka albo ręcznie). */
    @Column(name = "job_id", updatable = false)
    private UUID jobId;

    /** Dlaczego makro pominęło artykuł. */
    @Column(length = 300)
    private String failure;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CadPriorityRun() {
    }

    public CadPriorityRun(Store store, String topItm8, String itm8, String ean, String nrCad, Source source,
            UUID jobId, AppUser by) {
        this.jobId = jobId;
        this.store = store;
        this.topItm8 = topItm8;
        this.itm8 = itm8;
        this.ean = ean;
        this.nrCad = nrCad;
        this.source = source;
        this.createdBy = by;
        this.createdAt = Instant.now();
    }

    public void fail(String reason) {
        failure = reason.length() > 300 ? reason.substring(0, 300) : reason;
    }

    /** {@code by} puste: makro nie doszło do skutku. */
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

    public String getFailure() {
        return failure;
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
