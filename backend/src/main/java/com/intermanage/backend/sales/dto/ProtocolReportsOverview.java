package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.intermanage.backend.store.entity.ProtocolKind;

/**
 * Protokoły, z których sklep może zrobić raport (bez ukrytych w Ustawieniach sklepu → Pliki PROTO), i do kiedy są
 * wgrane dokumenty.
 */
public record ProtocolReportsOverview(List<Protocol> protocols, LocalDate documentsCoveredUntil,
        LocalDateTime documentsReportGeneratedAt) {

    /** {@code documentType} pusty: system sklepu nie miał jeszcze takiego dokumentu, więc raport nie ma danych. */
    public record Protocol(ProtocolKind kind, int number, String label, String documentType) {
    }
}
