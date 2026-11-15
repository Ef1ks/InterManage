package com.intermanage.backend.mea.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Lista MEA sklepu: {@code configured} mówi, czy sklep ma zapisany klucz API; {@code dateFrom} to najwcześniejszy dzień
 * z odpowiedzi portalu; {@code items} to pozycje z zakładką każdej z nich.
 */
public record MeaListResponse(boolean configured, LocalDate dateFrom, List<MeaItem> items) {

    public static MeaListResponse notConfigured() {
        return new MeaListResponse(false, null, List.of());
    }
}
