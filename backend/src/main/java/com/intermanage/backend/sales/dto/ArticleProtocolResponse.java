package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Protokół artykułu w statystykach: dokument inny niż przyjęcie i korekta, z ilością artykułu. {@code label} to rodzaj
 * do pokazania ("Likwidacja", "Gratis"), a {@code documentType} kod do linku.
 */
public record ArticleProtocolResponse(
        UUID documentId,
        String documentType,
        String documentNumber,
        LocalDateTime documentDate,
        String label,
        boolean gratis,
        BigDecimal quantity) {
}
