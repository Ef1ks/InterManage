package com.intermanage.backend.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Zapytanie wysłane do KSeF w kontekście NIP-u - historia do limitów, która przetrwa restart. Dane techniczne. */
@Entity
@Table(name = "ksef_api_calls")
public class KsefApiCall {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 10, updatable = false)
    private String nip;

    @Column(nullable = false, length = 20, updatable = false)
    private String operation;

    @Column(name = "called_at", nullable = false, updatable = false)
    private Instant calledAt;

    protected KsefApiCall() {
    }

    public KsefApiCall(String nip, String operation, Instant calledAt) {
        this.nip = nip;
        this.operation = operation;
        this.calledAt = calledAt;
    }

    public Instant getCalledAt() {
        return calledAt;
    }
}
