package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Wydruk raportu protokołu: te same parametry co raport, do tego klucze grup do wydruku ({@code groups}, puste albo
 * {@code null} = wszystkie; klucz pustej grupy, np. „Bez marki”, to pusty tekst, dlatego żądanie idzie w treści, nie
 * w adresie) i czy drukować artykuły pod grupami. {@code groupArticles} zawęża wybraną grupę do części jej artykułów
 * (klucz grupy → ITM8); grupa spoza mapy idzie w całości. {@code controlGroups} to dostawcy do kontroli strat
 * (własne grupy artykułów), których dotyczy wydruk; ich nazwy są w nagłówku.
 */
public record ProtocolReportPrintRequest(@NotNull ProtocolReportGrouping grouping, NomenclatureLevel level,
        @NotNull LocalDate from, @NotNull LocalDate to, LocalDate previousFrom, LocalDate previousTo,
        @Size(max = 5000) List<String> groups, boolean articles,
        @Size(max = 5000) Map<String, List<String>> groupArticles, @Size(max = 100) List<UUID> controlGroups) {
}
