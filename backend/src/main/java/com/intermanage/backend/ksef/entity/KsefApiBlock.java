package com.intermanage.backend.ksef.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Blokada zapytań do KSeF nałożona przez KSeF (HTTP 429) na kontekst NIP-u, do końca {@code Retry-After}. */
@Entity
@Table(name = "ksef_api_blocks")
public class KsefApiBlock {

    @Id
    @Column(length = 10)
    private String nip;

    @Column(name = "blocked_until", nullable = false)
    private Instant blockedUntil;

    protected KsefApiBlock() {
    }

    public KsefApiBlock(String nip, Instant blockedUntil) {
        this.nip = nip;
        this.blockedUntil = blockedUntil;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public void setBlockedUntil(Instant blockedUntil) {
        this.blockedUntil = blockedUntil;
    }
}
