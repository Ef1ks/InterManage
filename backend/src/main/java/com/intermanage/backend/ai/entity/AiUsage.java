package com.intermanage.backend.ai.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Jedno udane zapytanie do modelu AI z liczbą tokenów (historia, wiersz się nie zmienia). */
@Entity
@Table(name = "ai_usage")
public class AiUsage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", updatable = false)
    private UUID storeId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private AiFeature feature;

    @Column(nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "prompt_tokens", nullable = false, updatable = false)
    private int promptTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private int outputTokens;

    @Column(name = "thought_tokens", nullable = false, updatable = false)
    private int thoughtTokens;

    @Column(name = "cached_tokens", nullable = false, updatable = false)
    private int cachedTokens;

    @Column(name = "total_tokens", nullable = false, updatable = false)
    private int totalTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiUsage() {
    }

    public AiUsage(UUID storeId, UUID userId, AiFeature feature, String model, int promptTokens, int outputTokens,
            int thoughtTokens, int cachedTokens, int totalTokens) {
        this.storeId = storeId;
        this.userId = userId;
        this.feature = feature;
        this.model = model;
        this.promptTokens = promptTokens;
        this.outputTokens = outputTokens;
        this.thoughtTokens = thoughtTokens;
        this.cachedTokens = cachedTokens;
        this.totalTokens = totalTokens;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public AiFeature getFeature() {
        return feature;
    }

    public String getModel() {
        return model;
    }
}
