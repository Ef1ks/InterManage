package com.intermanage.backend.activity.component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.activity.entity.StoreEventKind;
import com.intermanage.backend.activity.entity.StoreEventLevel;

/**
 * Zdarzenie czekające na zapis w buforze, a przy braku bazy w pliku na dysku (wiersz JSON). Id to UUID v7 z chwili
 * zdarzenia, więc kolejność na liście się zgadza, także gdy wpis trafi do bazy dopiero po powrocie połączenia.
 */
public record StoreEventRecord(UUID id, Instant occurredAt, UUID storeId, UUID userId, StoreEventKind kind,
        StoreEventLevel level, String module, String method, String path, Integer status, Integer durationMs,
        String summary, String detail) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SUMMARY_LENGTH = 500;
    private static final int PATH_LENGTH = 500;
    private static final int DETAIL_LENGTH = 20_000;

    public StoreEventRecord {
        summary = cut(summary == null || summary.isBlank() ? "(bez opisu)" : summary, SUMMARY_LENGTH);
        path = cut(path, PATH_LENGTH);
        detail = cut(detail, DETAIL_LENGTH);
    }

    public static StoreEventRecord now(UUID storeId, UUID userId, StoreEventKind kind, StoreEventLevel level,
            String module, String method, String path, Integer status, Integer durationMs, String summary,
            String detail) {
        Instant now = Instant.now();
        return new StoreEventRecord(uuidV7(now), now, storeId, userId, kind, level, module, method, path, status,
                durationMs, summary, detail);
    }

    /** UUID v7 (RFC 9562): 48 bitów milisekund, potem losowe bity. */
    static UUID uuidV7(Instant at) {
        long millis = at.toEpochMilli();
        long high = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }

    private static String cut(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }
}
