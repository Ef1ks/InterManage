package com.intermanage.backend.push.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Token powiadomień push urządzenia z aplikacją mobilną; wylogowanie albo odrzucenie przez usługę to znacznik. */
@Entity
@Table(name = "user_push_tokens")
public class UserPushToken {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(name = "token", nullable = false, updatable = false, length = 300)
    private String token;

    @Column(name = "platform", nullable = false, updatable = false, length = 10)
    private String platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected UserPushToken() {
    }

    public UserPushToken(AppUser user, String token, String platform) {
        this.user = user;
        this.token = token;
        this.platform = platform;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        lastSeenAt = createdAt;
    }

    public void seen() {
        lastSeenAt = Instant.now();
    }

    public void markDeleted() {
        deletedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getToken() {
        return token;
    }
}
