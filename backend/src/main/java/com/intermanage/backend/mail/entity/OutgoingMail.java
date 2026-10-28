package com.intermanage.backend.mail.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Mail w kolejce do wysłania, a po wysłaniu wpis w historii. */
@Entity
@Table(name = "outgoing_mails")
public class OutgoingMail {

    private static final String REDACTED = "[Treść z jednorazowym linkiem nie jest przechowywana po wysłaniu]";
    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private MailKind kind;

    @Column(name = "recipient_email", nullable = false, length = 320, updatable = false)
    private String recipientEmail;

    @Column(name = "recipient_name", length = 200, updatable = false)
    private String recipientName;

    @Column(nullable = false, length = 300, updatable = false)
    private String subject;

    @Column(name = "text_body", nullable = false)
    private String textBody;

    @Column(name = "html_body", nullable = false)
    private String htmlBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    protected OutgoingMail() {
    }

    public OutgoingMail(MailKind kind, String recipientEmail, String recipientName, String subject, String textBody,
            String htmlBody) {
        this.kind = kind;
        this.recipientEmail = recipientEmail;
        this.recipientName = recipientName;
        this.subject = subject;
        this.textBody = textBody;
        this.htmlBody = htmlBody;
        this.createdAt = Instant.now();
        this.nextAttemptAt = createdAt;
    }

    public void markSent(Instant now) {
        attempts++;
        sentAt = now;
        lastError = null;
        redactSecret();
    }

    /** Nieudana próba: kolejna o {@code nextAttemptAt}, a bez niej mail zostaje porzucony. */
    public void markAttemptFailed(Instant now, String error, Instant nextAttemptAt) {
        attempts++;
        lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        if (nextAttemptAt == null) {
            failedAt = now;
            redactSecret();
        } else {
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private void redactSecret() {
        if (kind.isSecret()) {
            textBody = REDACTED;
            htmlBody = REDACTED;
        }
    }

    public UUID getId() {
        return id;
    }

    public MailKind getKind() {
        return kind;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getSubject() {
        return subject;
    }

    public String getTextBody() {
        return textBody;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }
}
