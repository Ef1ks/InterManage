package com.intermanage.backend.basedelivery.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Kod MEA wpisany w Mercalys (opis w V71__base_deliveries.sql); znacznik {@code deletedAt} razem z potwierdzeniem. */
@Entity
@Table(name = "mea_codes")
public class MeaCode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "confirmation_id", nullable = false, updatable = false)
    private MeaConfirmation confirmation;

    @Column(nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MeaCode() {
    }

    MeaCode(Store store, MeaConfirmation confirmation, String code) {
        this.store = store;
        this.confirmation = confirmation;
        this.code = code;
    }

    void delete(Instant at) {
        deletedAt = at;
    }

    void restore() {
        deletedAt = null;
    }

    public String getCode() {
        return code;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
