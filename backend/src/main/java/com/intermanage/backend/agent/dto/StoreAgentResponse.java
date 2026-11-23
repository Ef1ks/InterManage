package com.intermanage.backend.agent.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent sklepu na liście w Ustawieniach sklepu. {@code key} jest tylko w odpowiedzi na dodanie (pokazywany raz);
 * {@code online}: kontakt w ostatniej minucie. {@code leader}: główny agent sklepu (pilnuje folderów i aktualizuje dane
 * z Mercalys), {@code preferred}: odbiera tę rolę agentom niepreferowanym.
 */
public record StoreAgentResponse(UUID id, String name, Instant createdAt, String createdBy, Instant lastSeenAt,
        String lastVersion, String machineName, String machineUser, boolean online, boolean preferred,
        boolean leader, Instant deletedAt, String key) {
}
