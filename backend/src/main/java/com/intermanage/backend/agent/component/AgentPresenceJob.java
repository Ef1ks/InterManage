package com.intermanage.backend.agent.component;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
 * agentem a sobą. Stan poprzedni jest w pamięci (backend jest jeden); po restarcie pierwszy przebieg go uzupełnia.
 */
@Component
public class AgentPresenceJob {

    private final StoreAgentRepository agents;
    private final LiveEvents live;
    private final Map<UUID, Boolean> lastOnline = new HashMap<>();

    public AgentPresenceJob(StoreAgentRepository agents, LiveEvents live) {
        this.agents = agents;
        this.live = live;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @Transactional(readOnly = true)
    public synchronized void check() {
        Instant now = Instant.now();
        Map<UUID, StoreAgent> bestByStore = new HashMap<>();
        for (StoreAgent agent : agents.findActive()) {
            UUID storeId = agent.getStore().getId();
            StoreAgent best = bestByStore.get(storeId);
            if (best == null || StoreAgentService.online(agent, now) && !StoreAgentService.online(best, now)) {
                bestByStore.put(storeId, agent);
            }
        }
        Map<UUID, Boolean> current = new HashMap<>();
        bestByStore.forEach((storeId, agent) -> current.put(storeId, StoreAgentService.online(agent, now)));
        lastOnline.keySet().stream().filter(storeId -> !current.containsKey(storeId)).toList()
                .forEach(storeId -> current.put(storeId, false));
        current.forEach((storeId, online) -> {
            Boolean before = lastOnline.put(storeId, online);
            if (before != null && before != online) {
                StoreAgent agent = bestByStore.get(storeId);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("action", online ? "online" : "offline");
                if (agent != null && agent.getLastVersion() != null) {
                    fields.put("version", agent.getLastVersion());
                }
                fields.put("at", now);
                live.publish(storeId, "agent", fields);
            }
        });
    }
}
