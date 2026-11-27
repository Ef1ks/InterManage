package com.intermanage.backend.chat.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Profil w czacie: nick, opis i telefon, które osoba sama wpisała. Wiersz nadpisywany. */
@Entity
@Table(name = "chat_profiles")
public class ChatProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    private String nickname;

    private String about;

    private String phone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatProfile() {
    }

    public ChatProfile(UUID userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public void change(String nickname, String about, String phone) {
        this.nickname = nickname;
        this.about = about;
        this.phone = phone;
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAbout() {
        return about;
    }

    public String getPhone() {
        return phone;
    }
}
