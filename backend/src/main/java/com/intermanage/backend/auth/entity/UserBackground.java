package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.component.UserImages;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Zdjęcie w tle aplikacji jednego konta. Obowiązuje jedno; zastąpione i usunięte mają znacznik usunięcia. Plik leży
 * w R2 ({@code storage_key}), a starsze zdjęcia i te bez połączenia z R2 w bazie, więc {@code /api/auth/me} czyta
 * tylko projekcję ({@code UserBackgroundRepository.findSummary}).
 */
@Entity
@Table(name = "user_backgrounds")
public class UserBackground {

    public static final int DEFAULT_VEIL = 70;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 40, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    /** Klucz pliku w R2; {@code null} = zdjęcie zapisane w bazie ({@link #content}). */
    @Column(name = "storage_key", length = 200, updatable = false)
    private String storageKey;

    @Column(updatable = false)
    private byte[] content;

    @Column(name = "veil_percent", nullable = false)
    private short veilPercent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected UserBackground() {
    }

    public UserBackground(AppUser user, UserImages.Stored image, int veilPercent) {
        this.user = user;
        this.fileName = image.fileName();
        this.contentType = image.contentType();
        this.sizeBytes = image.sizeBytes();
        this.storageKey = image.storageKey();
        this.content = image.content();
        this.veilPercent = (short) veilPercent;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markRemoved(AppUser by) {
        removedAt = Instant.now();
        removedBy = by;
    }

    public void restore() {
        removedAt = null;
        removedBy = null;
    }

    public void changeVeil(int veilPercent) {
        this.veilPercent = (short) veilPercent;
    }

    public UUID getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getContentType() {
        return contentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public byte[] getContent() {
        return content;
    }

    public int getVeilPercent() {
        return veilPercent;
    }

    public boolean isRemoved() {
        return removedAt != null;
    }
}
