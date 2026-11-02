package com.intermanage.backend.meat.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Dokument WZ z pozycjami; {@code scanId} pusty, gdy wprowadzony bez skanu. */
public record MeatDeliveryResponse(UUID id, LocalDate deliveryDate, MeatSupplier supplier, String documentNumber,
        UUID scanId, String scanContentType, List<Line> lines, String createdBy, Instant createdAt, String updatedBy,
        Instant updatedAt, Instant deletedAt) {

    /** {@code article} pusty = sam zakup. */
    public record Line(UUID id, String documentName, String documentCode, BigDecimal unitPrice, MeatArticle article,
            boolean handwrittenCorrection, String note) {
    }
}
