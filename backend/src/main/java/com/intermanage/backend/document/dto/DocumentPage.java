package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Jedna strona listy dokumentów typu (po {@value com.intermanage.backend.service.DocumentService#PAGE_SIZE}).
 * {@code type.documentCount} to wszystkie dokumenty typu, {@code matchingCount} i sumy - te pasujące do wyszukiwania
 * (do wiersza "Razem"). {@code lastReportGeneratedAt} służy do ostrzeżenia o nieaktualnych danych.
 */
public record DocumentPage(
        DocumentTypeSummary type,
        LocalDateTime lastReportGeneratedAt,
        List<DocumentSummary> documents,
        int page,
        boolean hasMore,
        long matchingCount,
        BigDecimal purchaseTotal,
        BigDecimal saleTotal) {
}
