package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.async.DeferredResult;

import com.intermanage.backend.agent.component.AgentMachineStates;
import com.intermanage.backend.agent.dto.AgentMacroApi;

/**
 * Czekanie agenta na makra (od 1.9.0): jedno długie żądanie zamiast pytania co 3 s. Agent zgłasza stan komputera,
 * a serwer trzyma połączenie (bez blokowania wątku, {@link DeferredResult}) i odpowiada, gdy komputer dostanie makro.
 * Zmianę stanu (ktoś usiadł, blokada ekranu, odtwarzacz) agent zgłasza od razu nowym żądaniem, które zastępuje
 * poprzednie: komputer czeka najwyżej raz. Backend jest jeden, więc czekający są w pamięci.
 */
@Service
public class AgentMacroWaitService {

    private static final Logger log = LoggerFactory.getLogger(AgentMacroWaitService.class);
    /** Poniżej limitu bezczynności Cloudflare (100 s) i {@link AgentMacroService#LOST_AFTER}. */
    static final int MAX_WAIT_SECONDS = 55;

    private record Waiter(StoreAgentService.AgentContext agent, UUID handled,
            DeferredResult<AgentMacroApi.PollResponse> result) {
    }

    private final Map<UUID, Waiter> waiters = new ConcurrentHashMap<>();
    private final AgentMacroService macros;

    public AgentMacroWaitService(AgentMacroService macros) {
        this.macros = macros;
    }

    public DeferredResult<AgentMacroApi.PollResponse> await(StoreAgentService.AgentContext agent,
            AgentMacroApi.Wait request) {
        int seconds = Math.clamp(request.timeout() == null ? 25 : request.timeout(), 1, MAX_WAIT_SECONDS);
        DeferredResult<AgentMacroApi.PollResponse> result = new DeferredResult<>(seconds * 1000L,
                () -> new AgentMacroApi.PollResponse(null));
        Waiter waiter = new Waiter(agent, request.handled(), result);
        // Najpierw rejestracja, potem sprawdzenie: makro zlecone pomiędzy obudzi tego, kto już czeka.
        Waiter previous = waiters.put(agent.agentId(), waiter);
        if (previous != null) {
            previous.result().setResult(new AgentMacroApi.PollResponse(null));
        }
        result.onCompletion(() -> waiters.remove(agent.agentId(), waiter));
        try {
            AgentMacroApi.PollResponse response = macros.report(agent, request.state(),
                    Duration.ofSeconds(seconds).plus(AgentMachineStates.FRESH));
            answer(waiter, response);
        } catch (RuntimeException e) {
            waiters.remove(agent.agentId(), waiter);
            throw e;
        }
        return result;
    }

    /** Po zatwierdzeniu: makro zaproponowane komputerowi idzie do niego od razu. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOffered(AgentMacroService.MacroOffered event) {
        Waiter waiter = waiters.get(event.agentId());
        if (waiter == null) {
            return;
        }
        // W tle, żeby nie wydłużać żądania, które zleciło makro.
        Thread.ofVirtual().name("agent-macro-wake").start(() -> {
            try {
                answer(waiter, macros.offered(waiter.agent()));
            } catch (RuntimeException e) {
                log.warn("Nie udało się przekazać makra agentowi sklepu {}", waiter.agent().storeNumber(), e);
            }
        });
    }

    /** Czy ten agent właśnie czeka na makra (testy, diagnostyka). */
    public boolean waiting(UUID agentId) {
        return waiters.containsKey(agentId);
    }

    /** Makra, które agent już odlicza albo wykonuje, nie wysyłamy drugi raz: żądania goniłyby jedno drugie. */
    private static void answer(Waiter waiter, AgentMacroApi.PollResponse response) {
        if (response.offer() != null && !response.offer().id().equals(waiter.handled())) {
            waiter.result().setResult(response);
        }
    }
}
