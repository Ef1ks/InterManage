package com.intermanage.backend.promotion.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Raport promocji po odczytaniu: numer sklepu, data wydruku, zakres dat końca z nagłówka i unikalne promocje. */
public record ParsedPromotionReport(String storeNumber, LocalDateTime generatedAt, LocalDate endFrom,
        LocalDate endTo, List<PromotionReportRow> rows) {
}
