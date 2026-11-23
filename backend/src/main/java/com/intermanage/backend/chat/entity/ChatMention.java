package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Oznaczenie w wiadomości ({@code @osoba}, {@code @wszyscy}, {@code @dział}) tak, jak stoi w treści: {@code label}
 * z chwili wysłania, a {@code targetId} to konto albo dział. Kogo oznaczono, mówi {@code chat_mention_recipients}.
 */
@Entity
@Table(name = "chat_mentions")
public class ChatMention {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    private ChatMessage message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChatMentionKind kind;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "department_id", updatable = false)
    private UUID departmentId;

    @Column(nullable = false, updatable = false)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMention() {
    }

    public ChatMention(ChatMessage message, ChatMentionKind kind, UUID targetId, String label) {
        this.message = message;
        this.kind = kind;
        this.userId = kind == ChatMentionKind.USER ? targetId : null;
        this.departmentId = kind == ChatMentionKind.DEPARTMENT ? targetId : null;
        this.label = label;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public ChatMentionKind getKind() {
        return kind;
    }

    /** Konto albo dział; przy {@code @wszyscy} puste. */
    public UUID getTargetId() {
        return kind == ChatMentionKind.USER ? userId : departmentId;
    }

    public String getLabel() {
        return label;
    }
}
