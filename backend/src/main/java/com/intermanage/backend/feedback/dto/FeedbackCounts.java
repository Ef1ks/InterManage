package com.intermanage.backend.feedback.dto;

/** Liczby zgłoszeń według stanu (bez usuniętych); {@code fresh} = nowe, do licznika w menu. */
public record FeedbackCounts(long fresh, long inProgress, long done, long rejected) {
}
