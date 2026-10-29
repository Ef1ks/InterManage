package com.intermanage.backend.document.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ilość linii ruchu międzymagazynowego, która naprawdę przyjechała do sklepu docelowego. Ponowne wpisanie ilości
 * poprawia ten wpis; linia bez wpisu przyjechała w ilości z dokumentu.
 */
@Entity
@Table(name = "document_transfer_received_lines")
public class DocumentTransferReceivedLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "document_line_id", nullable = false, updatable = false)
    private UUID documentLineId;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    protected DocumentTransferReceivedLine() {
    }

    public DocumentTransferReceivedLine(UUID targetId, UUID documentLineId, BigDecimal quantity) {
        this.targetId = targetId;
        this.documentLineId = documentLineId;
        this.quantity = quantity;
    }

    public UUID getDocumentLineId() {
        return documentLineId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
