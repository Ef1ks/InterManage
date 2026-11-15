package com.intermanage.backend.mea.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

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
 * Klucze API portalu Grupy Muszkieterów dla sklepu: {@code apiKey} (KEY) loguje, {@code apiSecret} (SECRET) służy
 * dopiero do podpisania wysyłki zamówienia MEA. Oba zaszyfrowane {@link com.intermanage.backend.common.component.SecretCipher}.
 * Jeden wiersz na sklep, nadpisywany.
 */
@Entity
@Table(name = "mea_api_credentials")
public class MeaApiCredentials {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "api_secret")
    private String apiSecret;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeaApiCredentials() {
    }

    public MeaApiCredentials(Store store) {
        this.store = store;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Zapisuje zaszyfrowany KEY i (opcjonalnie) SECRET; pusty sekret zostawia dotychczasowy. */
    public void update(String apiKeyEncrypted, String apiSecretEncrypted, AppUser by) {
        this.apiKey = apiKeyEncrypted;
        if (apiSecretEncrypted != null) {
            this.apiSecret = apiSecretEncrypted;
        }
        this.updatedBy = by;
    }

    public Store getStore() {
        return store;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
