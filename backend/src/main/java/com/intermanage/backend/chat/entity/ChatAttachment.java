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
 * Załącznik wiadomości. Pliki (PDF, Excel…) mają treść w bazie; zdjęcia i filmy będą w prywatnym R2
 * ({@code storageKey}) z podglądem ThumbHash i datą wygaśnięcia. Listy czytają projekcję bez treści.
 */
@Entity
@Table(name = "chat_attachments")
public class ChatAttachment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    private ChatMessage message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChatAttachmentKind kind;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(updatable = false)
    private byte[] content;

    @Column(name = "storage_key", updatable = false)
    private String storageKey;

    @Column(updatable = false)
    private String thumbhash;

    @Column(updatable = false)
    private Integer width;

    @Column(updatable = false)
    private Integer height;

    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatAttachment() {
    }

    public static ChatAttachment file(ChatMessage message, String fileName, String contentType, byte[] content) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.message = message;
        attachment.kind = ChatAttachmentKind.FILE;
        attachment.fileName = fileName;
        attachment.contentType = contentType;
        attachment.content = content;
        attachment.sizeBytes = content.length;
        attachment.createdAt = Instant.now();
        return attachment;
    }

    public UUID getId() {
        return id;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public ChatAttachmentKind getKind() {
        return kind;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getContent() {
        return content;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
