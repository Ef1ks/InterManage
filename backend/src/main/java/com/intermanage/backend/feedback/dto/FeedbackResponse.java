package com.intermanage.backend.feedback.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.feedback.entity.FeedbackKind;
import com.intermanage.backend.feedback.entity.FeedbackStatus;

/** Zgłoszenie poprawki z opisami zrzutów (bez treści; treść pod osobnym adresem). */
public record FeedbackResponse(UUID id, FeedbackKind kind, FeedbackStatus status, String description, String pageUrl,
        String pageLabel, String userAgent, UUID storeId, String storeNumber, String storeName, String createdByName,
        Instant createdAt, String ownerNote, String statusChangedByName, Instant statusChangedAt,
        List<Attachment> attachments) {

    public record Attachment(UUID id, String fileName, String contentType, int sizeBytes) {
    }
}
