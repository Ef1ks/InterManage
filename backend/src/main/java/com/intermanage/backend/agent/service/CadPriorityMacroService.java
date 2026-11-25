package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.repository.AgentMacroJobRepository;
import com.intermanage.backend.agent.repository.StoreAgentRepository;
import com.intermanage.backend.cadtop.component.CadPriorityMacro;
import com.intermanage.backend.cadtop.dto.CadPriorityRequest;
import com.intermanage.backend.cadtop.repository.CadPrioritySettingsRepository;
import com.intermanage.backend.cadtop.service.CadPriorityService;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;

/**
 * ITM8 z cadencier na komputerze sklepu: zlecone z listy albo, gdy sklep to włączył, samo po aktualizacji danych.
 * Automat rusza po dzisiejszym raporcie kartoteki, gdy żaden komputer nie prowadzi już aktualizacji, na preferowanym
 * komputerze, po {@link #BATCH} artykułów, żeby między partiami zmieściły się inne makra sklepu. Partię anulowaną przy
 * komputerze ponawia po {@link #RETRY_AFTER}, a po {@link #MAX_FAILURES} błędach makra czeka do następnego dnia.
 */
@Service
public class CadPriorityMacroService {

    private static final Logger log = LoggerFactory.getLogger(CadPriorityMacroService.class);
    static final int BATCH = 60;
    static final int MAX_FAILURES = 2;
    static final Duration RETRY_AFTER = Duration.ofMinutes(20);

    private final AgentMacroService macros;
    private final AgentMacroJobRepository jobs;
    private final StoreAgentRepository agents;
    private final CadPriorityService priorities;
    private final CadPrioritySettingsRepository settings;
    private final LiveEvents live;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    /** Sklep, w którym nikt nie podjął makra: kolejna próba nie wcześniej niż wtedy. */
    private final Map<UUID, Instant> retryAt = new ConcurrentHashMap<>();

    public CadPriorityMacroService(AgentMacroService macros, AgentMacroJobRepository jobs,
            StoreAgentRepository agents, CadPriorityService priorities, CadPrioritySettingsRepository settings,
            LiveEvents live, TransactionTemplate transaction,
            @Value("${app.agent.cad-priority-macro.enabled:true}") boolean enabled) {
        this.macros = macros;
        this.jobs = jobs;
        this.agents = agents;
        this.priorities = priorities;
        this.settings = settings;
        this.live = live;
        this.transaction = transaction;
        this.enabled = enabled;
    }

    /** Zaznaczone artykuły z listy na komputer sklepu. */
    public AgentMacroApi.Job run(UUID storeId, CadPriorityRequest.Remote request, UUID userId) {
        return transaction.execute(status -> run(storeId,
                priorities.chosenForComputer(storeId, request.chosen()), request.agentId(), userId));
    }

    private AgentMacroApi.Job run(UUID storeId, CadPriorityService.Chosen chosen, UUID agentId, UUID userId) {
        AgentMacroApi.Job job = macros.cadPriorities(storeId, chosen.macroItems(), agentId, userId);
        if (job.status().equals(AgentMacroStatus.OFFERED.name())) {
            priorities.assignToJob(storeId, chosen, job.id(), userId);
        }
        return job;
    }

    @Scheduled(fixedDelayString = "${app.agent.cad-priority-macro.check-interval:PT1M}", initialDelayString = "PT2M")
    public void dispatchAfterUpdate() {
        if (enabled) {
            settings.findAutomaticStores().forEach(this::tryDispatch);
        }
    }

    private void tryDispatch(UUID storeId) {
        try {
            transaction.executeWithoutResult(status -> dispatch(storeId));
        } catch (ApiException e) {
            // W sklepie trwa inne makro: następna próba za chwilę.
            log.debug("ITM8 z cadencier w sklepie {} jeszcze nie ruszyły: {}", storeId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("ITM8 z cadencier w sklepie {}", storeId, e);
        }
    }

    private void dispatch(UUID storeId) {
        Instant retry = retryAt.get(storeId);
        if ((retry != null && retry.isAfter(Instant.now()))
                || !macros.canRun(storeId, AgentMacroKind.CAD_PRIORITY) || agents.isUpdating(storeId)
                || jobs.countByStoreIdAndKindAndStatusAndCreatedByIsNullAndCreatedAtAfter(storeId,
                        AgentMacroKind.CAD_PRIORITY, AgentMacroStatus.FAILED,
                        LocalDate.now(StoreTime.ZONE).atStartOfDay(StoreTime.ZONE).toInstant()) >= MAX_FAILURES) {
            return;
        }
        priorities.automaticItems(storeId, BATCH).ifPresent(chosen -> run(storeId, chosen, null, null));
    }

    /** Koniec makra: zlecenia artykułów dostają wynik. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterMacro(AgentMacroService.MacroEnded event) {
        if (event.kind() != AgentMacroKind.CAD_PRIORITY) {
            return;
        }
        Thread.ofVirtual().name("cad-priority-" + event.jobId()).start(() -> {
            try {
                transaction.executeWithoutResult(status -> settle(event));
                live.publish(event.storeId(), "cad-priority", Map.of("id", event.jobId()));
            } catch (RuntimeException e) {
                log.warn("Wynik makra ITM8 z cadencier {}", event.jobId(), e);
            }
        });
    }

    private void settle(AgentMacroService.MacroEnded event) {
        AgentMacroJob job = jobs.findByIdAndStoreId(event.jobId(), event.storeId()).orElseThrow();
        boolean done = event.status() == AgentMacroStatus.DONE;
        boolean ran = done || event.status() == AgentMacroStatus.FAILED;
        if (!ran && job.getCreatedBy() == null) {
            retryAt.put(event.storeId(), Instant.now().plus(RETRY_AFTER));
        }
        priorities.settle(job.getId(), done,
                ran ? CadPriorityMacro.completed(job.getMacro(), job.getResultMessage()) : Set.of(),
                ran ? CadPriorityMacro.skipped(job.getResultMessage()) : Map.of());
    }
}
