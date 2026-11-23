package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Rozmowa czatu: czat sklepu, grupa albo rozmowa dwóch osób ({@link ChatKind}). */
@Entity
@Table(name = "chat_conversations")
public class ChatConversation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChatKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", updatable = false)
    private Store store;

    private String name;

    @Column(name = "direct_key", updatable = false)
    private String directKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ChatConversation() {
    }

    private ChatConversation(ChatKind kind, Store store, String name, String directKey, AppUser createdBy) {
        this.kind = kind;
        this.store = store;
        this.name = name;
        this.directKey = directKey;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public static ChatConversation group(Store store, String name, AppUser createdBy) {
        return new ChatConversation(ChatKind.GROUP, store, name, null, createdBy);
    }

    public static ChatConversation direct(UUID first, UUID second, AppUser createdBy) {
        return new ChatConversation(ChatKind.DIRECT, null, null, directKey(first, second), createdBy);
    }

    /** Klucz pary kont, ten sam bez względu na to, kto zaczął rozmowę. */
    public static String directKey(UUID first, UUID second) {
        return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void touch(Instant at) {
        this.lastMessageAt = at;
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

    public ChatKind getKind() {
        return kind;
    }

    public Store getStore() {
        return store;
    }

    public String getName() {
        return name;
    }

    public String getDirectKey() {
        return directKey;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
