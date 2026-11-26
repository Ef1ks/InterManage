package com.intermanage.backend.product.entity;

import java.math.BigDecimal;
import java.util.Objects;
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

/**
 * Rodzaj opakowania zwrotnego sklepu z listy opakowań zwrotnych: kod kaucji z kartoteki i multikodów, nazwa
 * i wartość kaucji (np. 70 = KAUCJA PUSZKA METALOWA, 0,50 zł).
 */
@Entity
@Table(name = "deposit_kinds")
public class DepositKind {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "code", nullable = false, updatable = false)
    private short code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "vat_code")
    private Short vatCode;

    @Column(name = "value", nullable = false, precision = 10, scale = 2)
    private BigDecimal value;

    @Column(name = "kind", length = 50)
    private String kind;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_import_id", nullable = false, updatable = false)
    private DepositKindImport firstImport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_import_id", nullable = false)
    private DepositKindImport lastImport;

    protected DepositKind() {
    }

    public DepositKind(Store store, short code, DepositKindImport depositImport) {
        this.store = store;
        this.code = code;
        this.firstImport = depositImport;
        this.lastImport = depositImport;
    }

    /** Wartości z raportu; prawda, gdy coś się zmieniło. */
    public boolean update(String name, Short vatCode, BigDecimal value, String kind, DepositKindImport depositImport) {
        boolean changed = !Objects.equals(this.name, name) || !Objects.equals(this.vatCode, vatCode)
                || this.value == null || this.value.compareTo(value) != 0 || !Objects.equals(this.kind, kind);
        this.name = name;
        this.vatCode = vatCode;
        this.value = value;
        this.kind = kind;
        this.lastImport = depositImport;
        return changed;
    }

    public UUID getId() {
        return id;
    }

    public short getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Short getVatCode() {
        return vatCode;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getKind() {
        return kind;
    }
}
