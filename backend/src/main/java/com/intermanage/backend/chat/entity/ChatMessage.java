package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Wiadomość. Treści się nie zmienia; cofnięcie (dla wszystkich) to znacznik, który da się zdjąć. */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private ChatConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    private AppUser author;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retracted_by")
    private AppUser retractedBy;

    @Column(name = "retracted_at")
    private Instant retractedAt;

    protected ChatMessage() {
    }

    public ChatMessage(ChatConversation conversation, AppUser author, String body) {
        this.conversation = conversation;
        this.author = author;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public void retract(AppUser user) {
        retractedAt = Instant.now();
        retractedBy = user;
    }

    public void restore() {
        retractedAt = null;
        retractedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public ChatConversation getConversation() {
        return conversation;
    }

    public AppUser getAuthor() {
        return author;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getRetractedBy() {
        return retractedBy;
    }

    public Instant getRetractedAt() {
        return retractedAt;
    }
}
