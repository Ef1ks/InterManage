package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** PIN konta do przełączania kont w przeglądarce i to, czy przejście na konto go wymaga. */
@Entity
@Table(name = "user_switch_settings")
public class UserSwitchSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "pin_required", nullable = false)
    private boolean pinRequired = true;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserSwitchSettings() {
    }

    public UserSwitchSettings(AppUser user) {
        this.user = user;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getPinHash() {
        return pinHash;
    }

    public boolean hasPin() {
        return pinHash != null;
    }

    /** Nowy PIN zeruje błędne próby. */
    public void changePin(String pinHash) {
        this.pinHash = pinHash;
        this.failedAttempts = 0;
    }

    public boolean isPinRequired() {
        return pinRequired;
    }

    public void setPinRequired(boolean pinRequired) {
        this.pinRequired = pinRequired;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void failedAttempt() {
        failedAttempts++;
    }

    public void resetAttempts() {
        failedAttempts = 0;
    }
}
