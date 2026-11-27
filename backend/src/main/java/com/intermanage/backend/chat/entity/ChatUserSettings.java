package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ustawienia czatu konta: potwierdzenia odczytu i dźwięk. Wiersz nadpisywany (sam wybór), bez wiersza domyślne. */
@Entity
@Table(name = "chat_user_settings")
public class ChatUserSettings {

    /** Kiedy przeglądarka gra dźwięk nowej wiadomości. */
    public enum Sound {
        NONE, MENTIONS, ALL
    }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "read_receipts", nullable = false)
    private boolean readReceipts = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sound sound = Sound.MENTIONS;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatUserSettings() {
    }

    public ChatUserSettings(UUID userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public void change(boolean readReceipts, Sound sound) {
        this.readReceipts = readReceipts;
        this.sound = sound;
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isReadReceipts() {
        return readReceipts;
    }

    public Sound getSound() {
        return sound;
    }
}
