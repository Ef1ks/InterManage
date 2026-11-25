package com.intermanage.backend.agent.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.async.DeferredResult;

import com.intermanage.backend.agent.dto.AgentApi;
import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.service.StoreFileQueueService;

/**
 * Pliki z kolejki sklepu dla agenta: long polling. {@link #await} od razu oddaje czekające pliki przydzielone temu
 * agentowi, a bez nich trzyma połączenie (bez blokowania wątku, {@link DeferredResult}) do {@code timeout} sekund
 * i odpowiada, gdy tylko plik się pojawi ({@link #onFileQueued}). Backend jest jeden, więc czekający są w pamięci.
 * Czas czekania (25 s) jest poniżej limitu bezczynności Cloudflare (100 s).
 */
@Service
public class AgentFileQueueService {

    private static final Logger log = LoggerFactory.getLogger(AgentFileQueueService.class);
    /** Najwięcej plików w jednej odpowiedzi (każdy przydział jest ważny 2 minuty). */
    private static final int BATCH = 10;

    private record Waiter(StoreAgentService.AgentContext agent, DeferredResult<AgentApi.QueueFiles> result) {
    }

    private final Map<UUID, Set<Waiter>> waiters = new ConcurrentHashMap<>();
    private final StoreFileQueueService queue;
    private final StoreAgentService agents;
    private final StoreAgentFeatureService features;

    public AgentFileQueueService(StoreFileQueueService queue, StoreAgentService agents,
            StoreAgentFeatureService features) {
        this.features = features;
        this.queue = queue;
        this.agents = agents;
    }

    public DeferredResult<AgentApi.QueueFiles> await(StoreAgentService.AgentContext agent, int timeoutSeconds) {
        int seconds = Math.clamp(timeoutSeconds, 1, StoreAgentService.LONG_POLL_SECONDS);
        DeferredResult<AgentApi.QueueFiles> result = new DeferredResult<>(seconds * 1000L,
                () -> new AgentApi.QueueFiles(List.of()));
        Waiter waiter = new Waiter(agent, result);
        // Najpierw rejestracja, potem sprawdzenie: plik dodany pomiędzy obudzi tego, kto już czeka.
        Set<Waiter> set = waiters.computeIfAbsent(agent.storeId(), key -> ConcurrentHashMap.newKeySet());
        set.add(waiter);
        result.onCompletion(() -> set.remove(waiter));
        result.onTimeout(() -> set.remove(waiter));
        result.onError(error -> set.remove(waiter));
        deliver(waiter);
        return result;
    }

    /** Po zatwierdzeniu pliku w kolejce: budzi agentów sklepu, którzy czekają. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFileQueued(StoreFileQueueService.FileQueued event) {
        Set<Waiter> set = waiters.get(event.storeId());
        if (set == null || set.isEmpty()) {
            return;
        }
        // W tle, żeby nie wydłużać żądania, które dodało plik.
        List<Waiter> current = List.copyOf(set);
        Thread.ofVirtual().name("agent-wake").start(() -> current.forEach(this::deliver));
    }

    /** Liczba agentów sklepu, którzy właśnie czekają na pliki (testy, diagnostyka). */
    public int waiting(UUID storeId) {
        Set<Waiter> set = waiters.get(storeId);
        return set == null ? 0 : set.size();
    }

    private void deliver(Waiter waiter) {
        if (waiter.result().isSetOrExpired()) {
            return;
        }
        List<StoreFileQueueItem> items;
        try {
            // Sklep pracuje z folderami przez przeglądarkę: agent tylko utrzymuje kontakt, plików nie bierze.
            if (!agents.enabled(waiter.agent().storeId())
                    || !features.isOn(waiter.agent().storeId(), AgentFeature.FILE_SAVING)) {
                return;
            }
            List<String> folderKeys = List.copyOf(agents.folders(waiter.agent().storeId()).keySet());
            items = queue.claimForAgent(waiter.agent().storeId(), waiter.agent().agentId(), folderKeys, BATCH);
        } catch (RuntimeException e) {
            log.warn("Nie udało się przydzielić plików agentowi sklepu {}", waiter.agent().storeNumber(), e);
            return;
        }
        if (items.isEmpty()) {
            return;
        }
        AgentApi.QueueFiles files = new AgentApi.QueueFiles(items.stream()
                .map(item -> new AgentApi.QueueFile(item.getId(), item.getFolderKey(), item.getFileName(),
                        item.getContent().length, item.getCreatedAt()))
                .toList());
        if (!waiter.result().setResult(files)) {
            // Odpowiedź już poszła (czas minął): przydział wraca, żeby plik nie czekał 2 minuty.
            queue.releaseForAgent(waiter.agent().agentId(), items.stream().map(StoreFileQueueItem::getId).toList());
        }
    }
}
