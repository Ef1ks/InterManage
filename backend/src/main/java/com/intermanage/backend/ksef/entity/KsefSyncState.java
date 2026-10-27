package com.intermanage.backend.ksef.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Pobieranie faktur firmy (NIP) z KSeF: dokąd już pobrano i z jakim wynikiem. Wspólne dla wszystkich sklepów
 * z połączeniem w kontekście tego NIP-u, bo faktury są wspólne.
 */
@Entity
@Table(name = "ksef_sync_states")
public class KsefSyncState {

    @Id
    @Column(length = 10)
    private String nip;

    /** Faktury trwale zapisane w KSeF do tej chwili są już w bazie; kolejne pobieranie zaczyna od niej. */
    @Column(name = "sync_cursor")
    private Instant syncCursor;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "sync_ok")
    private Boolean syncOk;

    @Column(name = "sync_message", length = 1000)
    private String syncMessage;

    protected KsefSyncState() {
    }

    public KsefSyncState(String nip) {
        this.nip = nip;
    }

    public void recordSync(boolean ok, String message) {
        syncedAt = Instant.now();
        syncOk = ok;
        syncMessage = message;
    }

    public void setSyncCursor(Instant syncCursor) {
        this.syncCursor = syncCursor;
    }

    public String getNip() {
        return nip;
    }

    public Instant getSyncCursor() {
        return syncCursor;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public Boolean getSyncOk() {
        return syncOk;
    }

    public String getSyncMessage() {
        return syncMessage;
    }
}
