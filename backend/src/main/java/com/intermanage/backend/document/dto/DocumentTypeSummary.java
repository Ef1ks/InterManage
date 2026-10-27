package com.intermanage.backend.dto;

import java.time.LocalDateTime;

/**
 * Typ dokumentu na kafelku: kod (do ścieżek), pełne nazwy do wyświetlania, liczba dokumentów i data najnowszego.
 * W interfejsie pokazujemy tylko nazwy - kody z systemu sklepu są nieczytelne.
 */
public record DocumentTypeSummary(
        String code,
        String label,
        String singular,
        String description,
        long documentCount,
        LocalDateTime lastDocumentDate) {
}
