package com.intermanage.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Moduł Dokumenty: wszystkie typy (także bez dokumentów) i data najnowszego wgranego raportu Wejścia/Wyjścia
 * (czas lokalny sklepu); gdy nie jest z dzisiaj, frontend pokazuje, że dane są nieaktualne.
 */
public record DocumentsOverview(LocalDateTime lastReportGeneratedAt, List<DocumentTypeSummary> types) {
}
