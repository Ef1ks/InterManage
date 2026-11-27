package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Wyciszenie osoby albo rozmowy przez konto. Odciszenie to znacznik, ponowne wyciszenie to nowy wiersz. */
@Entity
@Table(name = "chat_mutes")
public class ChatMute {

    public enum Kind {
        USER, CONVERSATION
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected ChatMute() {
    }

    public ChatMute(UUID userId, Kind kind, UUID targetId) {
        this.userId = userId;
        this.kind = kind;
        this.targetId = targetId;
        this.createdAt = Instant.now();
    }

    public void remove() {
        removedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Kind getKind() {
        return kind;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
