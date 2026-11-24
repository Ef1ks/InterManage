package com.intermanage.backend.agent.component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Stan komputerów z agentem, zgłaszany przez agenta przy czekaniu na makra (od 1.9.0 przy każdej zmianie i co kilkadziesiąt
 * sekund, starszy agent co 3 s): ile sekund nikt nie ruszał myszy ani klawiatury, czy ekran jest zablokowany, czy działa
 * odtwarzacz makr i czy komputer wykonuje już makro.
 * Tylko w pamięci (backend jest jeden): po restarcie stan wraca przy następnym zgłoszeniu.
 */
@Component
public class AgentMachineStates {

    /** Stan bez odświeżenia tyle po zapowiedzianym czasie = agent nie czeka na makra (wyłączony, brak sieci). */
    public static final Duration FRESH = Duration.ofSeconds(20);

    /**
     * @param idleSeconds bezczynność w chwili zgłoszenia
     * @param playerVersion wersja odtwarzacza, np. {@code 0.65} ({@code null} = agent sprzed 1.8.0)
     * @param validUntil do kiedy stan obowiązuje bez kolejnego zgłoszenia
     */
    public record State(int idleSeconds, boolean locked, boolean playerRunning, boolean busy, String playerVersion,
            Instant reportedAt, Instant validUntil) {

        /** Odtwarzacz w wersji co najmniej {@code 0.<minor>}. */
        public boolean playerAtLeast(int minor) {
            if (playerVersion == null || !playerVersion.matches("\\d{1,4}\\.\\d{1,5}(\\..*)?")) {
                return false;
            }
            String[] parts = playerVersion.split("\\.");
            return Integer.parseInt(parts[0]) > 0 || Integer.parseInt(parts[1]) >= minor;
        }

        public boolean fresh(Instant now) {
            return validUntil.isAfter(now);
        }

        /** Ruch przy komputerze agent zgłasza od razu, więc między zgłoszeniami bezczynność tylko rośnie. */
        public int idleAt(Instant now) {
            return idleSeconds + (int) Math.clamp(Duration.between(reportedAt, now).toSeconds(), 0, 3600);
        }
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    public void report(UUID agentId, int idleSeconds, boolean locked, boolean playerRunning, boolean busy,
            String playerVersion, Instant now, Duration validFor) {
        states.put(agentId, new State(Math.max(0, idleSeconds), locked, playerRunning, busy, playerVersion, now,
                now.plus(validFor)));
    }

    /**
     * Od kiedy komputer milczy: ostatnie zgłoszenie, a bez niego start serwera. Restart (wdrożenie) w trakcie makra
     * nie może go uznać za zgubione, zanim agent zdąży się odezwać.
     */
    public Instant lastSeen(UUID agentId) {
        State state = agentId == null ? null : states.get(agentId);
        return state == null ? startedAt : state.reportedAt();
    }

    /** Czy komputer milczy dłużej, niż zapowiedział (po starcie serwera dopiero po {@link #FRESH}). */
    public boolean silent(UUID agentId, Instant now) {
        State state = agentId == null ? null : states.get(agentId);
        return state == null ? !startedAt.plus(FRESH).isAfter(now) : !state.fresh(now);
    }

    public Optional<State> get(UUID agentId) {
        return Optional.ofNullable(states.get(agentId));
    }
}
