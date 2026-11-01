package com.intermanage.backend.backup.entity;

import java.time.Instant;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Ustawienia kopii zapasowej bazy (jeden wiersz, {@link #ID}): harmonogram, liczba kopii na Dysku, hasło szyfrowania
 * i połączenie z kontem Google. Sekrety są zaszyfrowane {@code SecretCipher}, encja trzyma tylko szyfrogramy.
 */
@Entity
@Table(name = "backup_settings")
public class BackupSettings {

    public static final short ID = 1;

    @Id
    private Short id = ID;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "run_hour", nullable = false)
    private short runHour = 3;

    @Column(name = "keep_count", nullable = false)
    private short keepCount = 30;

    @Column(name = "password_encrypted")
    private String passwordEncrypted;

    @Column(name = "password_set_at")
    private Instant passwordSetAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "password_set_by")
    private AppUser passwordSetBy;

    @Column(name = "google_email")
    private String googleEmail;

    @Column(name = "google_refresh_token")
    private String googleRefreshToken;

    @Column(name = "google_folder_id")
    private String googleFolderId;

    @Column(name = "google_connected_at")
    private Instant googleConnectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "google_connected_by")
    private AppUser googleConnectedBy;

    @Column(name = "oauth_state")
    private String oauthState;

    @Column(name = "oauth_state_expires_at")
    private Instant oauthStateExpiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void update(boolean enabled, int runHour, int keepCount, AppUser by) {
        this.enabled = enabled;
        this.runHour = (short) runHour;
        this.keepCount = (short) keepCount;
        this.updatedBy = by;
    }

    public void setPassword(String encrypted, AppUser by) {
        this.passwordEncrypted = encrypted;
        this.passwordSetAt = Instant.now();
        this.passwordSetBy = by;
    }

    public void startOAuth(String state, Instant expiresAt) {
        this.oauthState = state;
        this.oauthStateExpiresAt = expiresAt;
    }

    /** Zużywa state logowania: pasuje tylko raz i tylko przed wygaśnięciem. */
    public boolean consumeOAuthState(String state) {
        boolean valid = oauthState != null && oauthState.equals(state) && oauthStateExpiresAt != null
                && Instant.now().isBefore(oauthStateExpiresAt);
        oauthState = null;
        oauthStateExpiresAt = null;
        return valid;
    }

    public void connectGoogle(String email, String encryptedRefreshToken, AppUser by) {
        if (!email.equalsIgnoreCase(String.valueOf(googleEmail))) {
            // Inne konto: folder z poprzedniego nie jest widoczny dla nowego, powstanie nowy.
            googleFolderId = null;
        }
        this.googleEmail = email;
        this.googleRefreshToken = encryptedRefreshToken;
        this.googleConnectedAt = Instant.now();
        this.googleConnectedBy = by;
    }

    /** Odłącza konto Google i wyłącza kopie; historia kopii zostaje. */
    public void disconnectGoogle(AppUser by) {
        this.googleRefreshToken = null;
        this.enabled = false;
        this.updatedBy = by;
    }

    public void setGoogleFolderId(String googleFolderId) {
        this.googleFolderId = googleFolderId;
    }

    public boolean isConnected() {
        return googleRefreshToken != null;
    }

    public boolean isPasswordSet() {
        return passwordEncrypted != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getRunHour() {
        return runHour;
    }

    public int getKeepCount() {
        return keepCount;
    }

    public String getPasswordEncrypted() {
        return passwordEncrypted;
    }

    public Instant getPasswordSetAt() {
        return passwordSetAt;
    }

    public AppUser getPasswordSetBy() {
        return passwordSetBy;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    public String getGoogleRefreshToken() {
        return googleRefreshToken;
    }

    public String getGoogleFolderId() {
        return googleFolderId;
    }

    public Instant getGoogleConnectedAt() {
        return googleConnectedAt;
    }

    public AppUser getGoogleConnectedBy() {
        return googleConnectedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }
}
