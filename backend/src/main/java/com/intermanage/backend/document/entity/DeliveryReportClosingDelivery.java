package com.intermanage.backend.document.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Przyjęcie w zamkniętym raporcie ({@link DeliveryReportClosing}). Bez {@code originClosing}: przyjęcie z okresu
 * zamknięcia z kwotami, jakie poszły w raporcie. Z {@code originClosing}: zmiana przyjęcia z wcześniej zamkniętego
 * okresu, która weszła do tego raportu (kwoty są różnicami). {@code correctionIds}: wszystkie przypięte korekty
 * w chwili zamknięcia. Wpis się nie zmienia.
 */
@Entity
@Table(name = "delivery_report_closing_deliveries")
public class DeliveryReportClosingDelivery {

    /** Zakup netto po korektach w rejonie nomenklatury ({@code regionCode} null = linie bez nomenklatury). */
    @Embeddable
    public record RegionAmount(
            @Column(name = "region_code", length = 2) String regionCode,
            @Column(name = "purchase_value", nullable = false, precision = 16, scale = 4) BigDecimal purchaseValue) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closing_id", nullable = false, updatable = false)
    private DeliveryReportClosing closing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, updatable = false)
    private Document delivery;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_closing_id", updatable = false)
    private DeliveryReportClosing originClosing;

    @Column(name = "purchase_value", nullable = false, updatable = false, precision = 16, scale = 4)
    private BigDecimal purchaseValue;

    @Column(name = "correction_value", nullable = false, updatable = false, precision = 16, scale = 4)
    private BigDecimal correctionValue;

    @ElementCollection
    @CollectionTable(name = "delivery_report_closing_regions", joinColumns = @JoinColumn(name = "closing_delivery_id"))
    @BatchSize(size = 200)
    private List<RegionAmount> regions = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "delivery_report_closing_corrections",
            joinColumns = @JoinColumn(name = "closing_delivery_id"))
    @Column(name = "correction_id")
    @BatchSize(size = 200)
    private Set<UUID> correctionIds = new HashSet<>();

    protected DeliveryReportClosingDelivery() {
    }

    public DeliveryReportClosingDelivery(DeliveryReportClosing closing, Document delivery,
            DeliveryReportClosing originClosing, BigDecimal purchaseValue, BigDecimal correctionValue,
            Map<String, BigDecimal> regions, Set<UUID> correctionIds) {
        this.closing = closing;
        this.delivery = delivery;
        this.originClosing = originClosing;
        this.purchaseValue = purchaseValue;
        this.correctionValue = correctionValue;
        regions.forEach((code, value) -> this.regions.add(new RegionAmount(code, value)));
        this.correctionIds.addAll(correctionIds);
    }

    public UUID getId() {
        return id;
    }

    public DeliveryReportClosing getClosing() {
        return closing;
    }

    public Document getDelivery() {
        return delivery;
    }

    public DeliveryReportClosing getOriginClosing() {
        return originClosing;
    }

    public BigDecimal getPurchaseValue() {
        return purchaseValue;
    }

    public BigDecimal getCorrectionValue() {
        return correctionValue;
    }

    public List<RegionAmount> getRegions() {
        return regions;
    }

    public Set<UUID> getCorrectionIds() {
        return correctionIds;
    }
}
