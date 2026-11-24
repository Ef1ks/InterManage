package com.intermanage.backend.agent.component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Stan komputerów z agentem, zgłaszany przez agenta co kilka sekund (czekanie na makra): ile sekund nikt nie ruszał
 * myszy ani klawiatury, czy ekran jest zablokowany, czy działa odtwarzacz makr i czy komputer wykonuje już makro.
 * Tylko w pamięci (backend jest jeden): po restarcie stan wraca przy następnym zgłoszeniu.
 */
@Component
public class AgentMachineStates {

    /** Stan starszy niż tyle = agent nie czeka na makra (wyłączony, stara wersja, brak sieci). */
    public static final Duration FRESH = Duration.ofSeconds(20);

    /** @param playerVersion wersja odtwarzacza, np. {@code 0.65} ({@code null} = agent sprzed 1.8.0) */
    public record State(int idleSeconds, boolean locked, boolean playerRunning, boolean busy, String playerVersion,
            Instant reportedAt) {

        /** Odtwarzacz w wersji co najmniej {@code 0.<minor>}. */
        public boolean playerAtLeast(int minor) {
            if (playerVersion == null || !playerVersion.matches("\\d{1,4}\\.\\d{1,5}(\\..*)?")) {
                return false;
            }
            String[] parts = playerVersion.split("\\.");
            return Integer.parseInt(parts[0]) > 0 || Integer.parseInt(parts[1]) >= minor;
        }

        public boolean fresh(Instant now) {
            return reportedAt.isAfter(now.minus(FRESH));
        }
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public void report(UUID agentId, int idleSeconds, boolean locked, boolean playerRunning, boolean busy,
            String playerVersion, Instant now) {
        states.put(agentId, new State(Math.max(0, idleSeconds), locked, playerRunning, busy, playerVersion, now));
    }

    public Optional<State> get(UUID agentId) {
        return Optional.ofNullable(states.get(agentId));
    }
}
