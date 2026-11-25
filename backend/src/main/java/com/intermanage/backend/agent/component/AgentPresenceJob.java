package com.intermanage.backend.agent.component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.agent.entity.StoreAgent;
import com.intermanage.backend.agent.repository.StoreAgentRepository;
import com.intermanage.backend.agent.service.StoreAgentService;
import com.intermanage.backend.common.component.LiveEvents;

/**
 * Co 15 s sprawdza, czy agenci sklepów są online (kontakt w ostatniej minucie), i przy zmianie stanu wysyła
 * przeglądarkom sklepu wiadomość {@code agent}: przeglądarka przełącza wtedy zapis i pilnowanie folderów między
 * agentem a sobą ({@code online} / {@code offline} całego sklepu), a osoby w sklepie dostają powiadomienie także o
 * jednym komputerze, który przestał się zgłaszać, gdy inne działają ({@code computer-offline}), i o zamkniętym
 * odtwarzaczu makr ({@code player-stopped}, po {@link #PLAYER_GONE_AFTER}, bo w trakcie makra potrafi się przeładować).
 * Stan poprzedni jest w pamięci (backend jest jeden); po restarcie pierwszy przebieg go uzupełnia.
 */
@Component
public class AgentPresenceJob {

    static final Duration PLAYER_GONE_AFTER = Duration.ofSeconds(60);

    private final StoreAgentRepository agents;
    private final AgentMachineStates states;
    private final LiveEvents live;
    private final Map<UUID, Boolean> storeOnline = new HashMap<>();
    private final Map<UUID, Boolean> agentOnline = new HashMap<>();
    /** Komputery, na których odtwarzacz działał; wartość = od kiedy go nie widać ({@code null} = działa). */
    private final Map<UUID, Instant> players = new HashMap<>();

    public AgentPresenceJob(StoreAgentRepository agents, AgentMachineStates states, LiveEvents live) {
        this.agents = agents;
        this.states = states;
        this.live = live;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @Transactional(readOnly = true)
    public synchronized void check() {
        Instant now = Instant.now();
        Map<UUID, List<StoreAgent>> byStore = agents.findActive().stream()
                .collect(Collectors.groupingBy(agent -> agent.getStore().getId()));
        // Sklep, w którym usunięto ostatniego agenta, też przechodzi na offline.
        storeOnline.keySet().forEach(storeId -> byStore.putIfAbsent(storeId, List.of()));
        byStore.forEach((storeId, storeAgents) -> {
            boolean online = storeAgents.stream().anyMatch(agent -> StoreAgentService.online(agent, now));
            Boolean before = storeOnline.put(storeId, online);
            if (before != null && before != online) {
                Map<String, Object> fields = fields(online ? "online" : "offline", now);
                storeAgents.stream().filter(agent -> StoreAgentService.online(agent, now)).findFirst()
                        .or(() -> storeAgents.stream().findFirst())
                        .map(StoreAgent::getLastVersion).ifPresent(version -> fields.put("version", version));
                live.publish(storeId, "agent", fields);
            }
            for (StoreAgent agent : storeAgents) {
                checkComputer(storeId, agent, online, now);
            }
        });
    }

    private void checkComputer(UUID storeId, StoreAgent agent, boolean storeIsOnline, Instant now) {
        boolean online = StoreAgentService.online(agent, now);
        Boolean before = agentOnline.put(agent.getId(), online);
        // Gdy zamilkł cały sklep, mówi to już jedna wiadomość.
        if (Boolean.TRUE.equals(before) && !online && storeIsOnline) {
            publish(storeId, "computer-offline", agent, now);
        }
        AgentMachineStates.State state = states.get(agent.getId()).orElse(null);
        if (!online || state == null || !state.fresh(now)) {
            // Wyłączony komputer to nie zamknięty odtwarzacz; po powrocie liczymy od nowa.
            players.remove(agent.getId());
        } else if (state.playerRunning()) {
            players.put(agent.getId(), null);
        } else if (players.containsKey(agent.getId()) && !state.busy()) {
            Instant since = players.get(agent.getId());
            if (since == null) {
                players.put(agent.getId(), now);
            } else if (!since.plus(PLAYER_GONE_AFTER).isAfter(now)) {
                players.remove(agent.getId());
                publish(storeId, "player-stopped", agent, now);
            }
        }
    }

    private void publish(UUID storeId, String action, StoreAgent agent, Instant now) {
        Map<String, Object> fields = fields(action, now);
        fields.put("agentId", agent.getId());
        fields.put("computer", agent.getName());
        live.publish(storeId, "agent", fields);
    }

    private static Map<String, Object> fields(String action, Instant now) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", action);
        fields.put("at", now);
        return fields;
    }
}
