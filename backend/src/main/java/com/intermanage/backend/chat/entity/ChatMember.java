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

/** Członek grupy albo rozmowy dwóch osób. Usunięcie z grupy to znacznik, ponowne dodanie to nowy wiersz. */
@Entity
@Table(name = "chat_members")
public class ChatMember {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private ChatConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by", updatable = false)
    private AppUser addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected ChatMember() {
    }

    public ChatMember(ChatConversation conversation, AppUser user, AppUser addedBy) {
        this.conversation = conversation;
        this.user = user;
        this.addedBy = addedBy;
        this.addedAt = Instant.now();
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public UUID getId() {
        return id;
    }

    public ChatConversation getConversation() {
        return conversation;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
