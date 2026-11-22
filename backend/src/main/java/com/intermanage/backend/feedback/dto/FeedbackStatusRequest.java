package com.intermanage.backend.feedback.dto;

import com.intermanage.backend.feedback.entity.FeedbackStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Zmiana stanu zgłoszenia przez właściciela, z notatką (pusta = bez notatki). */
public record FeedbackStatusRequest(@NotNull(message = "Wybierz stan") FeedbackStatus status,
        @Size(max = 2000, message = "Notatka może mieć najwyżej 2000 znaków") String note) {
}
