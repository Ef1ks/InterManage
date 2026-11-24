package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.agent.component.AgentMachineStates;
import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.entity.StoreAgent;
import com.intermanage.backend.agent.repository.AgentMacroJobRepository;
import com.intermanage.backend.agent.repository.StoreAgentRepository;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.basedelivery.component.Itm8Macro;
import com.intermanage.backend.basedelivery.component.MeaCodeMacro;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.MeaOverview;
import com.intermanage.backend.basedelivery.service.BaseDeliveryService;
import com.intermanage.backend.basedelivery.service.MeaCodeService;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.importguide.component.ReportSaveMacro;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Makra Mercalys zlecane z przeglądarki na wolnym komputerze sklepu, także z innego miasta. Treść makra składa
 * serwer (agent nie wykona dowolnego tekstu z przeglądarki).
 *
 * <p>Wybór komputera: agent sklepu zgłasza co kilka sekund stan komputera ({@link AgentMachineStates}). Zlecenie
 * dostaje komputer z działającym odtwarzaczem makr, odblokowanym ekranem i bez innego makra, najdłużej nieużywany
 * (wolny = {@link #FREE_AFTER} bez ruchu myszy i klawiatury), przy remisie preferowany. Agent pokazuje odliczanie
 * ({@link #COUNTDOWN_SECONDS}) z „Anuluj”: anulowanie albo brak odpowiedzi w {@link #OFFER_TIMEOUT} przekazuje
 * zlecenie kolejnemu komputerowi, a gdy żadnego nie ma, zlecenie kończy się „Żaden komputer nie jest wolny”.
 * W sklepie najwyżej jedno makro naraz (Mercalys jest jeden).
 */
@Service
public class AgentMacroService {

    public static final int COUNTDOWN_SECONDS = 5;
    /** Komputer bez ruchu myszy i klawiatury tyle czasu jest wolny (używane też dostają zlecenie, ale na końcu). */
    public static final Duration FREE_AFTER = Duration.ofMinutes(2);
    /** Tyle komputer ma na odliczanie i start (zgłoszenie co kilka sekund + odliczanie + zapas). */
    public static final Duration OFFER_TIMEOUT = Duration.ofSeconds(30);
    /** Agent bez zgłoszenia tyle czasu w trakcie makra = komputer przestał odpowiadać. */
    public static final Duration LOST_AFTER = Duration.ofMinutes(2);
    public static final Duration RUN_TIMEOUT = Duration.ofHours(2);
    /** Kody MEA: kilkadziesiąt kodów po 1-3 pliki, integracja jednego trwa do kilku minut. */
    public static final Duration MEA_RUN_TIMEOUT = Duration.ofHours(6);

    private final AgentMacroJobRepository jobs;
    private final StoreAgentRepository agents;
    private final AgentMachineStates states;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final BaseDeliveryService deliveries;
    private final MeaCodeService meaCodes;
    private final LiveEvents live;

    public AgentMacroService(AgentMacroJobRepository jobs, StoreAgentRepository agents, AgentMachineStates states,
            StoreRepository stores, AppUserRepository users, BaseDeliveryService deliveries, MeaCodeService meaCodes,
            LiveEvents live) {
        this.jobs = jobs;
        this.agents = agents;
        this.states = states;
        this.stores = stores;
        this.users = users;
        this.deliveries = deliveries;
        this.meaCodes = meaCodes;
        this.live = live;
    }

    // --- Przeglądarka ---

    /** Komputery sklepu z agentem: czy mogą teraz wykonać makro, a jeśli nie, dlaczego. */
    @Transactional(readOnly = true)
    public List<AgentMacroApi.Computer> computers(UUID storeId) {
        Instant now = Instant.now();
        return agents.findByStore(storeId).stream().filter(agent -> agent.getDeletedAt() == null)
                .map(agent -> computer(agent, now)).toList();
    }

    @Transactional
    public AgentMacroApi.Job itm8Collect(UUID storeId, AgentMacroApi.Itm8CollectRequest request, UUID userId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, request.deliveryId());
        List<String> suppliers = Itm8Macro.fileSuppliers(delivery);
        return create(storeId, AgentMacroKind.ITM8_COLLECT, "Zbierz brakujące ITM8 · bon " + delivery.number(),
                Itm8Macro.collect(delivery.number(), suppliers), 0, request.agentId(), userId);
    }

    @Transactional
    public AgentMacroApi.Job itm8Apply(UUID storeId, AgentMacroApi.Itm8ApplyRequest request, UUID userId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, request.deliveryId());
        Itm8Macro.Plan plan = Itm8Macro.plan(delivery, Itm8Macro.parseCodes(String.join("\n", request.codes())));
        if (plan.items().isEmpty()) {
            throw ApiException.badRequest("Żaden z kodów nie pasuje do linii dokumentu z EAN-em");
        }
        return create(storeId, AgentMacroKind.ITM8_APPLY, "Uzupełnij ITM8 · bon " + delivery.number() + " ("
                + plan.items().size() + " poz.)", Itm8Macro.apply(plan.items()), plan.items().size(),
                request.agentId(), userId);
    }

    /**
     * Wpisanie kodów MEA w Mercalys. Tylko kody z listy „Do wpisania” sklepu, w jej kolejności; samo wpisanie niczego
     * nie potwierdza, bo kod bez pliku w Mercalys musi zostać na liście.
     */
    @Transactional
    public AgentMacroApi.Job meaCodes(UUID storeId, AgentMacroApi.MeaCodesRequest request, UUID userId) {
        Set<String> chosen = new LinkedHashSet<>();
        request.codes().forEach(code -> chosen.add(code.strip()));
        List<String> codes = meaCodes.overview(storeId).pending().stream().map(MeaOverview.PendingCode::code)
                .filter(chosen::contains).toList();
        List<String> unknown = chosen.stream()
                .filter(code -> !codes.contains(code) || !MeaCodeMacro.isCode(code)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Tych kodów nie ma na liście do wpisania: " + String.join(", ", unknown)
                    + ". Odśwież stronę.");
        }
        return create(storeId, AgentMacroKind.MEA_CODES, "Wpisz kody MEA (" + codes.size() + ")",
                MeaCodeMacro.build(codes), codes.size(), request.agentId(), userId);
    }

    @Transactional
    public AgentMacroApi.Job get(UUID storeId, UUID id) {
        sweep(storeId, Instant.now());
        return response(job(storeId, id));
    }

    /** Zlecający (albo inna osoba z prawem zlecania) anuluje makro przed startem na komputerze. */
    @Transactional
    public AgentMacroApi.Job cancel(UUID storeId, UUID id, UUID userId) {
        sweep(storeId, Instant.now());
        AgentMacroJob job = job(storeId, id);
        if (job.getStatus() == AgentMacroStatus.RUNNING) {
            throw ApiException.conflict("Makro już działa na komputerze. Zatrzymać je można tylko tam (Esc).");
        }
        if (job.getStatus() == AgentMacroStatus.OFFERED) {
            job.cancel(users.getReferenceById(userId), Instant.now());
            announce(job);
        }
        return response(job);
    }

    private AgentMacroApi.Job create(UUID storeId, AgentMacroKind kind, String title, String macro, int itemCount,
            UUID targetAgentId, UUID userId) {
        Instant now = Instant.now();
        sweep(storeId, now);
        if (!jobs.findActiveForUpdate(storeId).isEmpty()) {
            throw ApiException.conflict("W sklepie trwa już makro na innym komputerze. Poczekaj, aż się skończy.");
        }
        if (targetAgentId != null && agents.findById(targetAgentId)
                .filter(agent -> agent.getDeletedAt() == null && agent.getStore().getId().equals(storeId))
                .isEmpty()) {
            throw ApiException.notFound("Nie znaleziono komputera sklepu");
        }
        AgentMacroJob job = new AgentMacroJob(stores.getReferenceById(storeId), kind, title, macro, itemCount,
                targetAgentId, users.getReferenceById(userId));
        offerNext(job, now);
        jobs.saveAndFlush(job);
        announce(job);
        return response(job);
    }

    // --- Agent ---

    /**
     * Zgłoszenie stanu komputera (co kilka sekund) i odpowiedź: makro zaproponowane temu komputerowi albo nic. Przy
     * okazji porządkuje trwające makro sklepu (brak odpowiedzi komputera, zgubiony komputer w trakcie makra).
     */
    @Transactional
    public AgentMacroApi.PollResponse poll(StoreAgentService.AgentContext agent, AgentMacroApi.Poll request) {
        Instant now = Instant.now();
        states.report(agent.agentId(), request.idleSeconds() == null ? 0 : request.idleSeconds(),
                Boolean.TRUE.equals(request.locked()), Boolean.TRUE.equals(request.playerRunning()),
                Boolean.TRUE.equals(request.busy()), now);
        sweep(agent.storeId(), now);
        AgentMacroJob offered = jobs.findActiveForUpdate(agent.storeId()).stream()
                .filter(job -> job.getStatus() == AgentMacroStatus.OFFERED && agent.agentId().equals(job.getAgentId()))
                .findFirst().orElse(null);
        if (offered == null) {
            return new AgentMacroApi.PollResponse(null);
        }
        return new AgentMacroApi.PollResponse(new AgentMacroApi.Offer(offered.getId(), offered.getTitle(),
                offered.getCreatedBy().getDisplayName(), COUNTDOWN_SECONDS));
    }

    /** Pracownik przy komputerze anulował odliczanie: zlecenie idzie do kolejnego komputera. */
    @Transactional
    public void decline(StoreAgentService.AgentContext agent, UUID id) {
        Instant now = Instant.now();
        AgentMacroJob job = offeredTo(agent, id);
        if (job == null) {
            return;
        }
        job.decline(agent.agentId());
        offerNext(job, now);
        announce(job);
    }

    /** Koniec odliczania: makro startuje na tym komputerze (409, gdy zlecenie już do niego nie należy). */
    @Transactional
    public AgentMacroApi.Start start(StoreAgentService.AgentContext agent, UUID id) {
        AgentMacroJob job = offeredTo(agent, id);
        if (job == null) {
            throw ApiException.conflict("Makro zostało anulowane albo przekazane innemu komputerowi");
        }
        job.start(Instant.now());
        announce(job);
        return new AgentMacroApi.Start(job.getMacro(), ReportSaveMacro.OPEN_LINE, job.getKind().name(),
                job.getKind() == AgentMacroKind.ITM8_COLLECT, (int) runTimeout(job).toMinutes());
    }

    private static Duration runTimeout(AgentMacroJob job) {
        return job.getKind() == AgentMacroKind.MEA_CODES ? MEA_RUN_TIMEOUT : RUN_TIMEOUT;
    }

    /** Wynik makra z odtwarzacza (zebrane kody przy „Zbierz brakujące ITM8”). */
    @Transactional
    public void finish(StoreAgentService.AgentContext agent, UUID id, AgentMacroApi.Finish request) {
        AgentMacroJob job = jobs.findByIdAndStoreId(id, agent.storeId())
                .filter(found -> found.getStatus() == AgentMacroStatus.RUNNING
                        && agent.agentId().equals(found.getAgentId()))
                .orElseThrow(() -> ApiException.conflict("To makro nie działa na tym komputerze"));
        Set<String> codes = new LinkedHashSet<>();
        if (request.codes() != null) {
            request.codes().forEach(code -> {
                String digits = code == null ? "" : code.replaceAll("\\D", "").replaceFirst("^0+", "");
                if (!digits.isEmpty() && digits.length() <= 13) {
                    codes.add(digits);
                }
            });
        }
        String message = request.message() == null || request.message().isBlank() ? null : request.message().strip();
        job.finish(Boolean.TRUE.equals(request.ok()) ? AgentMacroStatus.DONE : AgentMacroStatus.FAILED,
                message, List.copyOf(codes), Instant.now());
        announce(job);
    }

    private AgentMacroJob offeredTo(StoreAgentService.AgentContext agent, UUID id) {
        sweep(agent.storeId(), Instant.now());
        return jobs.findActiveForUpdate(agent.storeId()).stream()
                .filter(job -> job.getId().equals(id) && job.getStatus() == AgentMacroStatus.OFFERED
                        && agent.agentId().equals(job.getAgentId()))
                .findFirst().orElse(null);
    }

    // --- Przydział komputera ---

    /**
     * Trwające makro sklepu: komputer, który nie zaczął w {@link #OFFER_TIMEOUT} albo przestał się zgłaszać, traci
     * zlecenie na rzecz kolejnego; makro bez zgłoszeń komputera w trakcie kończy się błędem.
     */
    private void sweep(UUID storeId, Instant now) {
        for (AgentMacroJob job : jobs.findActiveForUpdate(storeId)) {
            Optional<AgentMachineStates.State> state = Optional.ofNullable(job.getAgentId()).flatMap(states::get);
            if (job.getStatus() == AgentMacroStatus.OFFERED) {
                boolean silent = state.map(found -> !found.fresh(now)).orElse(true);
                if (silent || job.getOfferedAt().plus(OFFER_TIMEOUT).isBefore(now)) {
                    job.decline(job.getAgentId());
                    offerNext(job, now);
                    announce(job);
                }
            } else if (job.getStatus() == AgentMacroStatus.RUNNING) {
                boolean lost = state.map(found -> found.reportedAt().plus(LOST_AFTER).isBefore(now)).orElse(true);
                if (lost || job.getStartedAt().plus(runTimeout(job)).isBefore(now)) {
                    job.finish(AgentMacroStatus.FAILED, "Komputer przestał odpowiadać w trakcie makra. Sprawdź w "
                            + "Mercalys, co zostało zrobione.", List.of(), now);
                    announce(job);
                }
            }
        }
    }

    /** Kolejny komputer dla zlecenia albo koniec „Żaden komputer nie jest wolny”. */
    private void offerNext(AgentMacroJob job, Instant now) {
        UUID storeId = job.getStore().getId();
        Map<StoreAgent, AgentMachineStates.State> candidates = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        for (StoreAgent agent : agents.findByStore(storeId)) {
            if (agent.getDeletedAt() != null || job.getDeclinedAgentIds().contains(agent.getId())
                    || (job.getTargetAgentId() != null && !job.getTargetAgentId().equals(agent.getId()))) {
                continue;
            }
            AgentMachineStates.State state = states.get(agent.getId()).orElse(null);
            String reason = unavailable(state, now);
            if (reason == null) {
                candidates.put(agent, state);
            } else {
                reasons.add(label(agent) + ": " + reason);
            }
        }
        StoreAgent best = candidates.keySet().stream()
                .min(Comparator.<StoreAgent, Boolean>comparing(agent -> !free(candidates.get(agent)))
                        .thenComparing(agent -> -candidates.get(agent).idleSeconds())
                        .thenComparing(agent -> !agent.isPreferred()))
                .orElse(null);
        if (best != null) {
            job.offer(best.getId(), now);
            return;
        }
        List<String> declined = job.getDeclinedAgentIds().stream().map(id -> agents.findById(id)
                .map(AgentMacroService::label).orElse("komputer")).toList();
        StringBuilder message = new StringBuilder("Żaden komputer sklepu nie jest wolny.");
        if (!declined.isEmpty()) {
            message.append(" Anulowane albo bez odpowiedzi: ").append(String.join(", ", declined)).append('.');
        }
        if (!reasons.isEmpty()) {
            message.append(' ').append(String.join("; ", reasons)).append('.');
        }
        if (declined.isEmpty() && reasons.isEmpty()) {
            message.append(" Sklep nie ma komputera z agentem.");
        }
        job.finish(AgentMacroStatus.NO_COMPUTER, message.toString(), List.of(), now);
    }

    private static boolean free(AgentMachineStates.State state) {
        return state.idleSeconds() >= FREE_AFTER.toSeconds();
    }

    /** Powód, dla którego komputer nie może teraz wykonać makra, albo {@code null}. */
    private static String unavailable(AgentMachineStates.State state, Instant now) {
        if (state == null || !state.fresh(now)) {
            return "agent nie odpowiada (wyłączony komputer albo stara wersja agenta)";
        }
        if (!state.playerRunning()) {
            return "nie działa odtwarzacz makr AutoJob w wersji 0.64 albo nowszej";
        }
        if (state.locked()) {
            return "ekran zablokowany";
        }
        if (state.busy()) {
            return "trwa inne makro";
        }
        return null;
    }

    private AgentMacroApi.Computer computer(StoreAgent agent, Instant now) {
        AgentMachineStates.State state = states.get(agent.getId()).orElse(null);
        String reason = unavailable(state, now);
        boolean online = StoreAgentService.online(agent, now);
        return new AgentMacroApi.Computer(agent.getId(), agent.getName(), agent.getMachineName(), online,
                reason == null, reason == null && free(state), state == null ? null : state.idleSeconds(), reason);
    }

    private static String label(StoreAgent agent) {
        return agent.getMachineName() == null ? agent.getName() : agent.getName() + " (" + agent.getMachineName() + ")";
    }

    private AgentMacroJob job(UUID storeId, UUID id) {
        return jobs.findByIdAndStoreId(id, storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono makra"));
    }

    private void announce(AgentMacroJob job) {
        live.publish(job.getStore().getId(), "agent-macro", Map.of("id", job.getId(), "status",
                job.getStatus().name()));
    }

    private AgentMacroApi.Job response(AgentMacroJob job) {
        String computer = job.getAgentId() == null ? null
                : agents.findById(job.getAgentId()).map(AgentMacroService::label).orElse(null);
        List<String> declined = job.getDeclinedAgentIds().stream()
                .map(id -> agents.findById(id).map(AgentMacroService::label).orElse("komputer")).toList();
        return new AgentMacroApi.Job(job.getId(), job.getKind().name(), job.getTitle(), job.getStatus().name(),
                computer, job.getCreatedBy().getDisplayName(), job.getCreatedAt(), job.getOfferedAt(),
                job.getStartedAt(), job.getFinishedAt(), COUNTDOWN_SECONDS, declined, job.getResultMessage(),
                job.getResultCodes(), job.getItemCount());
    }
}
