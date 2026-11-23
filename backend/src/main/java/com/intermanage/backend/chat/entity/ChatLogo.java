package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Logo czatu sklepu albo grupy (JPG, PNG, WEBP). Jedno obowiązuje na rozmowę; wymiana i usunięcie to znacznik. */
@Entity
@Table(name = "chat_conversation_logos")
public class ChatLogo {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private ChatConversation conversation;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false, updatable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ChatLogo() {
    }

    public ChatLogo(ChatConversation conversation, String contentType, byte[] content, AppUser createdBy) {
        this.conversation = conversation;
        this.contentType = contentType;
        this.content = content;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void markDeleted(AppUser user) {
        deletedAt = Instant.now();
        deletedBy = user;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public ChatConversation getConversation() {
        return conversation;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
