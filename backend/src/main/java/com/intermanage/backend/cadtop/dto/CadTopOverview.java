package com.intermanage.backend.cadtop.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Cadencier sklepu: numery z planu z plikami TOP jego rejonu, wgrane plany i ostrzeżenia. {@code region} pusty = portal
 * nie pokazał jeszcze sklepowi plików TOP; {@code configured}: sklep ma klucz API portalu.
 */
public record CadTopOverview(boolean configured, String region, List<Row> rows, List<Plan> plans,
        List<String> warnings) {

    public enum Status {
        /** Zastąpiony kolejnym numerem. */
        PAST,
        /** Obowiązuje dziś. */
        CURRENT,
        /** Wejdzie w dniu {@code startsOn}. */
        UPCOMING,
        /** Plik TOP z numerem, którego nie ma w planie. */
        NOT_PLANNED
    }

    /** {@code file} pusty = portal nie ma jeszcze pliku tego numeru. */
    public record Row(String nrCad, LocalDate startsOn, LocalDate endsOn, Status status, File file) {
    }

    public record File(String fileName, String publishedAt, Instant downloadedAt, int articleCount, int btopCount,
            int bCount, int aCount, int newCount) {
    }

    public record Plan(int year, String fileName, int entryCount, Instant modifiedAt, Instant uploadedAt,
            String uploadedBy, boolean fromPortal) {
    }

    /** Wynik „Sprawdź portal”. */
    public record Sync(int downloadedTops, int downloadedPlans, CadTopOverview overview) {
    }
}
