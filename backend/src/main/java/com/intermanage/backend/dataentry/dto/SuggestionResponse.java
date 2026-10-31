package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.dataentry.entity.SuggestionStatus;

/**
 * Propozycje dla nowych artykułów. {@code modelUsed = false}: model nie odpowiedział (albo nie jest skonfigurowany),
 * więc propozycje są tylko z kartoteki, a {@code message} mówi dlaczego.
 */
public record SuggestionResponse(boolean modelUsed, String message, List<Suggestion> items) {

    /**
     * {@code subfamilyCode} jest puste przy {@link SuggestionStatus#TO_CHECK}: wybiera człowiek z {@code proposals}
     * (do 5, od najbardziej prawdopodobnej). {@code unitMismatch}: model podał inną miarę niż nazwa z faktury.
     */
    public record Suggestion(String key, UUID id, String name, String brand, String unit, BigDecimal netContent,
            boolean unitMismatch, String subfamilyCode, SuggestionStatus status, BigDecimal confidence,
            List<Proposal> proposals) {
    }

    public record Proposal(String code, List<Source> sources) {
    }

    /** Skąd propozycja: model, podobne nazwy z kartoteki, inne produkty producenta (prefiks EAN). */
    public enum Source {
        MODEL, NAMES, PRODUCER
    }
}
