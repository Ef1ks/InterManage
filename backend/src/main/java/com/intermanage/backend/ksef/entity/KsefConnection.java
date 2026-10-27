package com.intermanage.backend.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Połączenie sklepu z KSeF: certyfikat KSeF osoby z uprawnieniem do odczytu faktur, jego klucz prywatny (zaszyfrowany
 * hasłem, tak jak przyszedł z KSeF) i hasło zaszyfrowane kluczem aplikacji. {@code nip} to kontekst logowania (NIP
 * firmy) - faktury i ich pobieranie ({@link KsefSyncState}) są wspólne dla wszystkich sklepów z tym NIP-em. Wynik
 * ostatniego testu logowania jest zapisany do pokazania w ustawieniach.
 */
@Entity
@Table(name = "ksef_connections")
public class KsefConnection {

    @Id
    @Column(name = "store_id")
    private UUID storeId;

    @Column(nullable = false, length = 10)
    private String nip;

    @Column(name = "certificate_pem", nullable = false)
    private String certificatePem;

    @Column(name = "private_key_pem", nullable = false)
    private String privateKeyPem;

    @Column(name = "password_encrypted", nullable = false)
    private String passwordEncrypted;

    @Column(name = "certificate_subject", nullable = false, length = 300)
    private String certificateSubject;

    @Column(name = "certificate_valid_to", nullable = false)
    private Instant certificateValidTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "tested_at")
    private Instant testedAt;

    @Column(name = "test_ok")
    private Boolean testOk;

    @Column(name = "test_message", length = 1000)
    private String testMessage;

    protected KsefConnection() {
    }

    public KsefConnection(UUID storeId) {
        this.storeId = storeId;
    }

    /** Nowe dane logowania; wynik poprzedniego testu przestaje obowiązywać. */
    public void setCredentials(String nip, String certificatePem, String privateKeyPem, String passwordEncrypted,
            String certificateSubject, Instant certificateValidTo, AppUser by) {
        this.nip = nip;
        this.certificatePem = certificatePem;
        this.privateKeyPem = privateKeyPem;
        this.passwordEncrypted = passwordEncrypted;
        this.certificateSubject = certificateSubject;
        this.certificateValidTo = certificateValidTo;
        updatedBy = by;
        updatedAt = Instant.now();
        testedAt = null;
        testOk = null;
        testMessage = null;
    }

    /** Dane logowania z połączenia innego sklepu tej samej firmy (podłączenie bez wgrywania certyfikatu). */
    public void copyCredentials(KsefConnection source, AppUser by) {
        setCredentials(source.nip, source.certificatePem, source.privateKeyPem, source.passwordEncrypted,
                source.certificateSubject, source.certificateValidTo, by);
    }

    public void recordTest(boolean ok, String message) {
        testedAt = Instant.now();
        testOk = ok;
        testMessage = message;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getNip() {
        return nip;
    }

    public String getCertificatePem() {
        return certificatePem;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public String getPasswordEncrypted() {
        return passwordEncrypted;
    }

    public String getCertificateSubject() {
        return certificateSubject;
    }

    public Instant getCertificateValidTo() {
        return certificateValidTo;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getTestedAt() {
        return testedAt;
    }

    public Boolean getTestOk() {
        return testOk;
    }

    public String getTestMessage() {
        return testMessage;
    }
}
