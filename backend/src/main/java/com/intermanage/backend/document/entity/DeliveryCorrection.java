package com.intermanage.backend.document.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Korekta (błędu sklepu albo od dostawcy) przypięta do przyjęcia PZ, którego dotyczy. Odpięcie to znacznik, ponowne
 * przypięcie tworzy nowy wpis.
 */
@Entity
@Table(name = "delivery_corrections")
public class DeliveryCorrection {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, updatable = false)
    private Document delivery;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "correction_id", nullable = false, updatable = false)
    private Document correction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by", updatable = false)
    private AppUser linkedBy;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unlinked_by")
    private AppUser unlinkedBy;

    @Column(name = "unlinked_at")
    private Instant unlinkedAt;

    protected DeliveryCorrection() {
    }

    public DeliveryCorrection(Document delivery, Document correction, AppUser linkedBy) {
        this.delivery = delivery;
        this.correction = correction;
        this.linkedBy = linkedBy;
        this.linkedAt = Instant.now();
    }

    public void unlink(AppUser by) {
        unlinkedBy = by;
        unlinkedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Document getDelivery() {
        return delivery;
    }

    public Document getCorrection() {
        return correction;
    }

    public AppUser getLinkedBy() {
        return linkedBy;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
