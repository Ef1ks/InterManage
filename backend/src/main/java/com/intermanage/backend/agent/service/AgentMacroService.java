package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.agent.component.AgentMachineStates;
import com.intermanage.backend.agent.component.ProductFileMacro;
import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.entity.StoreAgent;
import com.intermanage.backend.agent.repository.AgentMacroJobRepository;
import com.intermanage.backend.agent.repository.StoreAgentRepository;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.basedelivery.component.BaseReceiveMacro;
import com.intermanage.backend.basedelivery.component.Itm8Macro;
import com.intermanage.backend.basedelivery.component.MeaCodeMacro;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.MeaOverview;
import com.intermanage.backend.basedelivery.service.BaseDeliveryService;
import com.intermanage.backend.basedelivery.service.BaseReceivingService;
import com.intermanage.backend.basedelivery.service.MeaCodeService;
import com.intermanage.backend.cadtop.component.CadPriorityMacro;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.PriceControlMacro;
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
 * zlecenie kolejnemu komputerowi.
 *
 * <p>Kolejka: w sklepie najwyżej jedno makro naraz. Zlecenie z przeglądarki, które nie może ruszyć od razu (trwa
 * inne makro, żaden komputer nie jest wolny, wszyscy anulowali), czeka w bazie ({@link AgentMacroStatus#QUEUED}) i
 * rusza samo, po kolei według chwili zlecenia ({@link #dispatch}); nie wygasa, zdejmuje je tylko „Anuluj”. Kolejkę
 * przesuwa każde zgłoszenie agenta (agent czeka na makro jednym długim żądaniem po drugim, więc zgłasza się co
 * najwyżej co minutę) i każdy odczyt stanu, a kolejka leży w bazie: restart serwera albo zamknięta przeglądarka
 * niczego nie gubią. Makra serwera (pliki nowych artykułów, zmiany cen, ITM8 po
 * aktualizacji) mają własne czekanie w swoich tabelach i ustępują kolejce: przy trwającym albo czekającym makrze
 * dostają 409 i serwer ponawia je sam.
 */
@Service
public class AgentMacroService {

    public static final int COUNTDOWN_SECONDS = 5;
    /** Pliki nowych artykułów: zapisujący miał już chwilę na „Anuluj” w przeglądarce. */
    public static final int PRODUCT_FILES_COUNTDOWN_SECONDS = 2;
    /** Zmiany cen: tyle bez ruchu przy komputerze. */
    public static final int PRICE_CONTROL_COUNTDOWN_SECONDS = 2;
    /** Komputer bez ruchu myszy i klawiatury tyle czasu jest wolny (używane też dostają zlecenie, ale na końcu). */
    public static final Duration FREE_AFTER = Duration.ofMinutes(2);
    /** Tyle komputer ma na odliczanie i start (zgłoszenie co kilka sekund + odliczanie + zapas). */
    public static final Duration OFFER_TIMEOUT = Duration.ofSeconds(30);
    /** Agent bez zgłoszenia tyle czasu w trakcie makra = komputer przestał odpowiadać. */
    public static final Duration LOST_AFTER = Duration.ofMinutes(2);
    public static final Duration RUN_TIMEOUT = Duration.ofHours(2);
    /** Kody MEA (integracja jednego pliku trwa do kilku minut) i ITM8 z cadencier (setki kart po ok. 10 s). */
    public static final Duration LONG_RUN_TIMEOUT = Duration.ofHours(6);
    /** Tyle wstecz i tyle makr pokazuje lista ostatnich makr sklepu. */
    private static final Duration RECENT = Duration.ofHours(12);
    private static final int RECENT_LIMIT = 15;
    private static final String SERVER_REQUEST = "Intermanage, po aktualizacji danych";
    /** Po „Anuluj” na wszystkich komputerach zlecenie z kolejki wraca do nich dopiero po tym czasie. */
    public static final Duration DECLINED_RETRY = Duration.ofMinutes(10);
    /** Tyle wyniku makra idzie w powiadomieniu na żywo; całość jest przy makrze. */
    private static final int MESSAGE_LENGTH = 600;
    private static final String WAITS_FOR_EARLIER = "czeka na wcześniejsze makro sklepu";
    /** Zgłoszenie „wolny” liczy się jako koniec makra dopiero tyle po starcie (zgłoszenie sprzed startu w drodze). */
    private static final Duration RESULT_GRACE = Duration.ofSeconds(30);

    /** Makra dostawy z bazy: polecenia odtwarzacza od 0.67 i wysyłka bonów przez agenta od 1.8.0. */
    private static final Set<AgentMacroKind> BASE_KINDS = Set.of(AgentMacroKind.BASE_RECEIVE,
            AgentMacroKind.BASE_CLOSE);
    private static final int BASE_PLAYER_MINOR = 67;
    /** Makra zlecane przez serwer po pojawieniu się pliku: wynik idzie w powiadomieniu. */
    private static final Set<AgentMacroKind> SERVER_KINDS = Set.of(AgentMacroKind.PRODUCT_FILES,
            AgentMacroKind.PRICE_CONTROL);
    private static final Pattern ORDER_CODE = Pattern.compile("(\\d{1,10})=(\\d{1,20})");
    private static final Pattern CLOSED_CODE = Pattern.compile("zamkniete=(\\d{1,20})");

    /** Makro zaproponowane komputerowi (budzi agenta, który na nie czeka). */
    public record MacroOffered(UUID agentId) {
    }

    /** Makro sklepu się skończyło (wynik, błąd, anulowanie, brak komputera): może ruszyć następne. */
    public record MacroEnded(UUID storeId, UUID jobId, AgentMacroKind kind, AgentMacroStatus status) {
    }

    private final AgentMacroJobRepository jobs;
    private final StoreAgentRepository agents;
    private final AgentMachineStates states;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final BaseDeliveryService deliveries;
    private final MeaCodeService meaCodes;
    private final BaseReceivingService receiving;
    private final LiveEvents live;
    private final ApplicationEventPublisher events;
    private final StoreAgentFeatureService features;

    public AgentMacroService(AgentMacroJobRepository jobs, StoreAgentRepository agents, AgentMachineStates states,
            StoreRepository stores, AppUserRepository users, BaseDeliveryService deliveries, MeaCodeService meaCodes,
            BaseReceivingService receiving, LiveEvents live, ApplicationEventPublisher events,
            StoreAgentFeatureService features) {
        this.features = features;
        this.events = events;
        this.receiving = receiving;
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
                Itm8Macro.collect(delivery.number(), suppliers), delivery.number(), 0, request.agentId(), userId);
    }

    @Transactional
    public AgentMacroApi.Job itm8Apply(UUID storeId, AgentMacroApi.Itm8ApplyRequest request, UUID userId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, request.deliveryId());
        Itm8Macro.Plan plan = Itm8Macro.plan(delivery, Itm8Macro.parseCodes(String.join("\n", request.codes())));
        if (plan.items().isEmpty()) {
            throw ApiException.badRequest("Żaden z kodów nie pasuje do linii dokumentu z EAN-em");
        }
        return create(storeId, AgentMacroKind.ITM8_APPLY, "Uzupełnij ITM8 · bon " + delivery.number() + " ("
                + plan.items().size() + " poz.)", Itm8Macro.apply(plan.items()), delivery.number(),
                plan.items().size(), request.agentId(), userId);
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
                MeaCodeMacro.build(codes), null, codes.size(), request.agentId(), userId);
    }

    /** Pliki bonu z Obsługi BL bazy do zamówień w Mercalys i bony Excel do „Znajdź różnice”. */
    @Transactional
    public AgentMacroApi.Job baseReceive(UUID storeId, AgentMacroApi.BaseDeliveryRequest request, UUID userId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, request.deliveryId());
        return create(storeId, AgentMacroKind.BASE_RECEIVE, "Wprowadź dostawę z bazy · bon " + delivery.number(),
                receiving.receiveMacro(storeId, delivery), delivery.number(), 0, request.agentId(), userId);
    }

    /** Nr BL w nagłówkach zamówień bonu i zakończenie dostaw, po sprawdzeniu różnic przez zlecającego. */
    @Transactional
    public AgentMacroApi.Job baseClose(UUID storeId, AgentMacroApi.BaseDeliveryRequest request, UUID userId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, request.deliveryId());
        List<BaseReceiveMacro.Order> orders = receiving.ordersToClose(storeId, delivery.number());
        if (orders.isEmpty()) {
            throw ApiException.conflict("Bon " + delivery.number() + " nie ma zamówień do zakończenia");
        }
        return create(storeId, AgentMacroKind.BASE_CLOSE, "Zakończ dostawę z bazy · bon " + delivery.number(),
                BaseReceiveMacro.close(delivery.number(), orders, LocalDate.now(StoreTime.ZONE)),
                delivery.number(), orders.size(), request.agentId(), userId);
    }

    /** Pliki nowych artykułów zapisane przez agenta ({@link ProductFileMacroService}). */
    @Transactional
    public AgentMacroApi.Job productFiles(UUID storeId, List<ProductFileMacro.File> files, UUID userId) {
        String first = files.getFirst().path();
        return create(storeId, AgentMacroKind.PRODUCT_FILES, "Wczytaj nowe artykuły · " + (files.size() == 1
                ? "dostawca " + files.getFirst().supplierCode() + ", " + first.substring(first.lastIndexOf('\\') + 1)
                : "pliki: " + files.size()),
                ProductFileMacro.build(files), null, files.size(), null, userId);
    }

    /** Zmiany cen z folderu sklepu ({@link PriceControlMacroService}), na preferowanym komputerze. */
    @Transactional
    public AgentMacroApi.Job priceControl(UUID storeId, List<PriceControlMacro.File> files, UUID userId) {
        int prices = files.stream().mapToInt(PriceControlMacro.File::lineCount).sum();
        return create(storeId, AgentMacroKind.PRICE_CONTROL, "Zmiana cen (" + prices + ") · " + (files.size() == 1
                ? files.getFirst().title() : "dokumenty: " + files.size()),
                PriceControlMacro.build(files), null, files.size(), null, userId);
    }

    /** ITM8 z cadencier ({@link CadPriorityMacroService}); {@code userId} puste = samo, po aktualizacji danych. */
    @Transactional
    public AgentMacroApi.Job cadPriorities(UUID storeId, List<CadPriorityMacro.Item> items, UUID agentId,
            UUID userId) {
        return create(storeId, AgentMacroKind.CAD_PRIORITY, "ITM8 z cadencier (" + items.size() + ")",
                CadPriorityMacro.build(items), null, items.size(), agentId, userId);
    }

    /** Czy któryś komputer sklepu może teraz dostać makro serwera (trwające makro sklepu odrzuci samo zlecenie). */
    @Transactional(readOnly = true)
    public boolean canRun(UUID storeId, AgentMacroKind kind) {
        Instant now = Instant.now();
        return eligible(storeId, true).stream()
                .anyMatch(agent -> unavailable(states.get(agent.getId()).orElse(null), now, kind) == null);
    }

    /** Zmiany cen i automatyczne ITM8 z cadencier: preferowany komputer i chwila bez ruchu przed startem. */
    private static boolean preferredOnly(AgentMacroJob job) {
        return job.getKind() == AgentMacroKind.PRICE_CONTROL
                || job.getKind() == AgentMacroKind.CAD_PRIORITY && job.getCreatedBy() == null;
    }

    /** Komputery, na które może iść makro: tylko preferowane, o ile sklep jakieś ma, albo wszystkie. */
    private List<StoreAgent> eligible(UUID storeId, boolean preferredOnly) {
        List<StoreAgent> active = agents.findByStore(storeId).stream()
                .filter(agent -> agent.getDeletedAt() == null).toList();
        List<StoreAgent> preferred = !preferredOnly ? List.of()
                : active.stream().filter(StoreAgent::isPreferred).toList();
        return preferred.isEmpty() ? active : preferred;
    }

    /** Czy którykolwiek komputer sklepu zgłosił odtwarzacz w wersji co najmniej {@code 0.<minor>}. */
    @Transactional(readOnly = true)
    public boolean anyPlayer(UUID storeId, int minor) {
        return agents.findByStore(storeId).stream().filter(agent -> agent.getDeletedAt() == null)
                .anyMatch(agent -> states.get(agent.getId()).filter(state -> state.playerAtLeast(minor)).isPresent());
    }

    /** Trwające i ostatnie makra sklepu, żeby strona po odświeżeniu dalej pokazywała, co robi komputer. */
    @Transactional
    public AgentMacroApi.Activity activity(UUID storeId) {
        Instant now = Instant.now();
        sweep(storeId, now);
        jobs.flush();
        Map<UUID, String> labels = new HashMap<>();
        agents.findByStore(storeId).forEach(agent -> labels.put(agent.getId(), label(agent)));
        List<UUID> queue = jobs.findQueuedIds(storeId);
        List<AgentMacroJobRepository.Recent> found = new ArrayList<>(jobs.findPending(storeId));
        found.addAll(jobs.findRecent(storeId, now.minus(RECENT), Limit.of(RECENT_LIMIT)));
        found.sort(Comparator.comparing(AgentMacroJobRepository.Recent::getCreatedAt).reversed());
        List<AgentMacroApi.Job> recent = found.stream()
                .map(job -> new AgentMacroApi.Job(job.getId(), job.getKind().name(), job.getTitle(),
                        job.getStatus().name(), job.getAgentId() == null ? null : labels.get(job.getAgentId()),
                        job.getRequestedBy() == null ? SERVER_REQUEST : job.getRequestedBy(), job.getCreatedAt(),
                        job.getOfferedAt(), job.getStartedAt(), job.getFinishedAt(), countdown(job.getKind()),
                        job.getDeclinedAgentIds().stream().map(id -> labels.getOrDefault(id, "komputer")).toList(),
                        job.getResultMessage(), job.getResultCodes(), job.getItemCount(), job.getReference(),
                        queue.contains(job.getId()) ? queue.indexOf(job.getId()) + 1 : null, job.getWaitReason()))
                .toList();
        return new AgentMacroApi.Activity(recent, agents.findRunningUpdate(storeId)
                .map(update -> new AgentMacroApi.Update(update.getMachineName() == null ? update.getName()
                        : update.getName() + " (" + update.getMachineName() + ")", update.getStartedAt()))
                .orElse(null));
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
        if (job.getStatus() == AgentMacroStatus.OFFERED || job.getStatus() == AgentMacroStatus.QUEUED) {
            job.cancel(users.getReferenceById(userId), Instant.now());
            announce(job);
            jobs.flush();
            dispatch(storeId, Instant.now());
        }
        return response(job);
    }

    /** „Przywróć” po anulowaniu: zlecenie wraca do kolejki na swoje miejsce (tylko takie, które nie ruszyło). */
    @Transactional
    public AgentMacroApi.Job restore(UUID storeId, UUID id) {
        AgentMacroJob job = job(storeId, id);
        if (job.getStatus() != AgentMacroStatus.CANCELLED || job.getStartedAt() != null || !queueable(job)) {
            throw ApiException.conflict("To makro nie czeka już na start");
        }
        job.restore();
        jobs.flush();
        dispatch(storeId, Instant.now());
        announce(job);
        return response(job);
    }

    private AgentMacroApi.Job create(UUID storeId, AgentMacroKind kind, String title, String macro,
            String reference, int itemCount, UUID targetAgentId, UUID userId) {
        Instant now = Instant.now();
        sweep(storeId, now);
        // Makra po zapisie pliku i po aktualizacji danych mają własne przełączniki.
        boolean automatic = kind == AgentMacroKind.PRODUCT_FILES || kind == AgentMacroKind.PRICE_CONTROL
                || userId == null;
        if (!automatic && !features.isOn(storeId, AgentFeature.REMOTE_MACROS)) {
            throw ApiException.conflict("Makra zlecane z przeglądarki są w tym sklepie wyłączone "
                    + "(Ustawienia sklepu → Agent sklepu).");
        }
        if (targetAgentId != null && agents.findById(targetAgentId)
                .filter(agent -> agent.getDeletedAt() == null && agent.getStore().getId().equals(storeId))
                .isEmpty()) {
            throw ApiException.notFound("Nie znaleziono komputera sklepu");
        }
        // Blokada kolejki przed sprawdzeniem trwającego: równoległe przydziały idą po kolei.
        List<AgentMacroJob> queued = jobs.findQueuedForUpdate(storeId);
        boolean busy = !jobs.findActiveForUpdate(storeId).isEmpty();
        AgentMacroJob job = new AgentMacroJob(stores.getReferenceById(storeId), kind, title, macro, reference,
                itemCount, targetAgentId, userId == null ? null : users.getReferenceById(userId));
        if (!queueable(job)) {
            // Makra serwera czekają u siebie i ustępują zleceniom z przeglądarki.
            if (busy || !queued.isEmpty()) {
                throw ApiException.conflict(busy ? "W sklepie trwa już makro na innym komputerze."
                        : "W sklepie czekają makra zlecone z przeglądarki.");
            }
            offerNext(job, now, false);
            jobs.saveAndFlush(job);
            announce(job);
            return response(job);
        }
        job.queue(busy ? WAITS_FOR_EARLIER : null, null);
        jobs.saveAndFlush(job);
        if (!busy) {
            dispatch(storeId, now);
        }
        if (job.getStatus() == AgentMacroStatus.QUEUED) {
            if (job.getWaitReason() == null) {
                job.waiting(WAITS_FOR_EARLIER);
            }
            announce(job);
        }
        return response(job);
    }

    /**
     * Zlecenie osoby z przeglądarki czeka w kolejce. Makro serwera (pliki nowych artykułów, zmiany cen, ITM8 po
     * aktualizacji danych) czeka u siebie, więc bez komputera kończy się „Żaden komputer nie jest wolny”.
     */
    private static boolean queueable(AgentMacroJob job) {
        return job.getCreatedBy() != null && !SERVER_KINDS.contains(job.getKind());
    }

    /**
     * Pierwsze zlecenie z kolejki, które może ruszyć, dostaje komputer. Po kolei według chwili zlecenia; zlecenie
     * czekające na konkretny (wyłączony) komputer albo po „Anuluj” nie blokuje kolejnych.
     */
    private void dispatch(UUID storeId, Instant now) {
        List<AgentMacroJob> queued = jobs.findQueuedForUpdate(storeId);
        if (queued.isEmpty() || !jobs.findActiveForUpdate(storeId).isEmpty()) {
            return;
        }
        for (AgentMacroJob job : queued) {
            if (job.getRetryAt() != null && job.getRetryAt().isAfter(now)) {
                continue;
            }
            offerNext(job, now, false);
            if (job.getStatus() == AgentMacroStatus.OFFERED) {
                // Pozostałe czekają na to makro.
                queued.stream().filter(other -> other.getStatus() == AgentMacroStatus.QUEUED
                        && (other.getRetryAt() == null || !other.getRetryAt().isAfter(now)))
                        .forEach(other -> other.waiting(WAITS_FOR_EARLIER));
                jobs.flush();
                announce(job);
                return;
            }
        }
    }

    // --- Agent ---

    /** Zgłoszenie stanu co kilka sekund (agent sprzed 1.9.0). */
    @Transactional
    public AgentMacroApi.PollResponse poll(StoreAgentService.AgentContext agent, AgentMacroApi.Poll request) {
        return report(agent, request, AgentMachineStates.FRESH);
    }

    /**
     * Zgłoszenie stanu komputera i odpowiedź: makro zaproponowane temu komputerowi albo nic. Przy okazji porządkuje
     * trwające makro sklepu (brak odpowiedzi komputera, zgubiony komputer w trakcie makra).
     */
    @Transactional
    public AgentMacroApi.PollResponse report(StoreAgentService.AgentContext agent, AgentMacroApi.Poll request,
            Duration validFor) {
        Instant now = Instant.now();
        states.report(agent.agentId(), request.idleSeconds() == null ? 0 : request.idleSeconds(),
                Boolean.TRUE.equals(request.locked()), Boolean.TRUE.equals(request.playerRunning()),
                Boolean.TRUE.equals(request.busy()), request.playerVersion(), now, validFor);
        sweep(agent.storeId(), now);
        return offered(agent);
    }

    /** Makro zaproponowane temu komputerowi albo nic. */
    @Transactional
    public AgentMacroApi.PollResponse offered(StoreAgentService.AgentContext agent) {
        AgentMacroJob offered = jobs.findActiveForUpdate(agent.storeId()).stream()
                .filter(job -> job.getStatus() == AgentMacroStatus.OFFERED && agent.agentId().equals(job.getAgentId()))
                .findFirst().orElse(null);
        if (offered == null) {
            return new AgentMacroApi.PollResponse(null);
        }
        return new AgentMacroApi.PollResponse(new AgentMacroApi.Offer(offered.getId(), offered.getTitle(),
                requestedBy(offered), countdown(offered.getKind()), preferredOnly(offered)));
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
        offerNext(job, now, true);
        announce(job);
        if (job.getStatus() != AgentMacroStatus.OFFERED) {
            jobs.flush();
            dispatch(agent.storeId(), now);
        }
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
                job.getKind() == AgentMacroKind.ITM8_COLLECT, (int) runTimeout(job).toMinutes(),
                uploads(job), job.getKind() == AgentMacroKind.PRODUCT_FILES
                        ? ProductFileMacro.files(job.getMacro()) : List.of());
    }

    private static List<AgentMacroApi.Upload> uploads(AgentMacroJob job) {
        return job.getKind() != AgentMacroKind.BASE_RECEIVE ? List.of()
                : BaseReceiveMacro.noteFiles(job.getMacro()).stream()
                        .map(file -> new AgentMacroApi.Upload(file.supplierCode(), file.path())).toList();
    }

    /** Plik zapisany przez makro (bon dostawy z bazy), tylko z listy z jego startu. */
    @Transactional
    public void file(StoreAgentService.AgentContext agent, UUID id, String key, MultipartFile file) {
        AgentMacroJob job = running(agent, id);
        if (uploads(job).stream().noneMatch(upload -> upload.key().equals(key))) {
            throw ApiException.badRequest("Makro nie zapisuje takiego pliku");
        }
        receiving.saveNote(agent.storeId(), job.getReference(), key, file);
    }

    private AgentMacroJob running(StoreAgentService.AgentContext agent, UUID id) {
        return jobs.findByIdAndStoreId(id, agent.storeId())
                .filter(found -> found.getStatus() == AgentMacroStatus.RUNNING
                        && agent.agentId().equals(found.getAgentId()))
                .orElseThrow(() -> ApiException.conflict("To makro nie działa na tym komputerze"));
    }

    /** Numery zamówień i zakończone dostawy z wyniku makra (także nieudanego: to, co zdążyło zrobić). */
    private List<String> baseResult(AgentMacroJob job, List<String> codes) {
        UUID storeId = job.getStore().getId();
        List<String> saved = new ArrayList<>();
        for (String code : codes) {
            Matcher order = ORDER_CODE.matcher(code == null ? "" : code.strip());
            Matcher closed = CLOSED_CODE.matcher(code == null ? "" : code.strip());
            if (job.getKind() == AgentMacroKind.BASE_RECEIVE && order.matches()) {
                receiving.orderRead(storeId, job.getReference(), order.group(1), order.group(2));
                saved.add(order.group());
            } else if (job.getKind() == AgentMacroKind.BASE_CLOSE && closed.matches()) {
                receiving.closed(storeId, job.getReference(), closed.group(1));
                saved.add(closed.group());
            }
        }
        return saved;
    }

    private static int countdown(AgentMacroKind kind) {
        return switch (kind) {
            case PRODUCT_FILES -> PRODUCT_FILES_COUNTDOWN_SECONDS;
            case PRICE_CONTROL -> PRICE_CONTROL_COUNTDOWN_SECONDS;
            default -> COUNTDOWN_SECONDS;
        };
    }

    private static Duration runTimeout(AgentMacroJob job) {
        return job.getKind() == AgentMacroKind.MEA_CODES || job.getKind() == AgentMacroKind.CAD_PRIORITY
                ? LONG_RUN_TIMEOUT : RUN_TIMEOUT;
    }

    /** Wynik makra z odtwarzacza (zebrane kody przy „Zbierz brakujące ITM8”). */
    @Transactional
    public void finish(StoreAgentService.AgentContext agent, UUID id, AgentMacroApi.Finish request) {
        AgentMacroJob job = running(agent, id);
        Set<String> codes = new LinkedHashSet<>();
        if (BASE_KINDS.contains(job.getKind())) {
            codes.addAll(baseResult(job, request.codes() == null ? List.of() : request.codes()));
        } else if (request.codes() != null) {
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
        // Następne z kolejki od razu, zanim makra serwera po końcu tego makra spróbują ruszyć same.
        jobs.flush();
        dispatch(agent.storeId(), Instant.now());
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
            if (job.getStatus() == AgentMacroStatus.OFFERED) {
                if (states.silent(job.getAgentId(), now) || job.getOfferedAt().plus(OFFER_TIMEOUT).isBefore(now)) {
                    job.decline(job.getAgentId());
                    offerNext(job, now, false);
                    announce(job);
                }
            } else if (job.getStatus() == AgentMacroStatus.RUNNING) {
                boolean lost = states.lastSeen(job.getAgentId()).plus(LOST_AFTER).isBefore(now);
                if (lost || job.getStartedAt().plus(runTimeout(job)).isBefore(now)) {
                    job.finish(AgentMacroStatus.FAILED, "Komputer przestał odpowiadać w trakcie makra. Sprawdź w "
                            + "Mercalys, co zostało zrobione.", List.of(), now);
                    announce(job);
                } else if (resultLost(job, now)) {
                    job.finish(AgentMacroStatus.FAILED, "Makro skończyło się na komputerze, ale wynik nie doszedł do "
                            + "serwera (np. restart serwera w trakcie). Sprawdź w Mercalys, co zostało zrobione.",
                            List.of(), now);
                    announce(job);
                }
            }
        }
        jobs.flush();
        dispatch(storeId, now);
    }

    /**
     * Agent trzyma „zajęty” od odliczania do wysłania wyniku (wynik ponawia przez ok. 100 s). Komputer, który po
     * starcie makra zgłasza się jako wolny, makro już skończył, a wynik przepadł (np. restart serwera dłuższy niż
     * ponawianie): bez tego kolejka sklepu stałaby do {@link #RUN_TIMEOUT}.
     */
    private boolean resultLost(AgentMacroJob job, Instant now) {
        AgentMachineStates.State state = states.get(job.getAgentId()).orElse(null);
        return state != null && state.fresh(now) && !state.busy()
                && state.reportedAt().isAfter(job.getStartedAt().plus(RESULT_GRACE));
    }

    /**
     * Kolejny komputer dla zlecenia. Bez wolnego komputera zlecenie z przeglądarki wraca do kolejki (po „Anuluj” na
     * komputerze dopiero za {@link #DECLINED_RETRY}), a makro serwera kończy się „Żaden komputer nie jest wolny”.
     */
    private void offerNext(AgentMacroJob job, Instant now, boolean declinedByPerson) {
        UUID storeId = job.getStore().getId();
        Map<StoreAgent, AgentMachineStates.State> candidates = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        for (StoreAgent agent : eligible(storeId, preferredOnly(job))) {
            if (job.getDeclinedAgentIds().contains(agent.getId())
                    || (job.getTargetAgentId() != null && !job.getTargetAgentId().equals(agent.getId()))) {
                continue;
            }
            AgentMachineStates.State state = states.get(agent.getId()).orElse(null);
            String reason = unavailable(state, now, job.getKind());
            if (reason == null) {
                candidates.put(agent, state);
            } else {
                reasons.add(label(agent) + ": " + reason);
            }
        }
        StoreAgent best = candidates.keySet().stream()
                .min(Comparator.<StoreAgent, Boolean>comparing(agent -> !free(candidates.get(agent), now))
                        .thenComparing(agent -> -candidates.get(agent).idleAt(now))
                        .thenComparing(agent -> !agent.isPreferred()))
                .orElse(null);
        if (best != null) {
            job.offer(best.getId(), now);
            events.publishEvent(new MacroOffered(best.getId()));
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
        if (!queueable(job)) {
            job.finish(AgentMacroStatus.NO_COMPUTER, message.toString(), List.of(), now);
            return;
        }
        String reason = reasons.isEmpty() ? (declined.isEmpty() ? "sklep nie ma komputera z agentem"
                : "brak wolnego komputera") : String.join("; ", reasons);
        if (declinedByPerson) {
            job.queue("anulowane na komputerze (" + String.join(", ", declined) + "), kolejna próba za "
                    + DECLINED_RETRY.toMinutes() + " min", now.plus(DECLINED_RETRY));
        } else if (job.getStatus() != AgentMacroStatus.QUEUED) {
            job.queue(reason, null);
        } else {
            job.waiting(reason);
        }
    }

    private static boolean free(AgentMachineStates.State state, Instant now) {
        return state.idleAt(now) >= FREE_AFTER.toSeconds();
    }

    /** Powód, dla którego komputer nie może teraz wykonać makra, albo {@code null}. */
    private static String unavailable(AgentMachineStates.State state, Instant now, AgentMacroKind kind) {
        if (state == null || !state.fresh(now)) {
            return "agent nie odpowiada (wyłączony komputer albo stara wersja agenta)";
        }
        if (!state.playerRunning()) {
            return "nie działa odtwarzacz makr AutoJob w wersji 0.64 albo nowszej (albo makra są wyłączone w agencie)";
        }
        if (kind != null && BASE_KINDS.contains(kind) && !state.playerAtLeast(BASE_PLAYER_MINOR)) {
            return "potrzebny agent 1.8.0 i odtwarzacz makr AutoJob 0.67 albo nowsze";
        }
        if (kind == AgentMacroKind.PRODUCT_FILES && !state.playerAtLeast(ProductFileMacro.PLAYER_MINOR)) {
            return "potrzebny odtwarzacz makr AutoJob 0." + ProductFileMacro.PLAYER_MINOR + " albo nowszy";
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
        String reason = unavailable(state, now, null);
        boolean online = StoreAgentService.online(agent, now);
        return new AgentMacroApi.Computer(agent.getId(), agent.getName(), agent.getMachineName(), online,
                reason == null, reason == null && free(state, now), state == null ? null : state.idleAt(now),
                reason);
    }

    private static String label(StoreAgent agent) {
        return agent.getMachineName() == null ? agent.getName() : agent.getName() + " (" + agent.getMachineName() + ")";
    }

    private AgentMacroJob job(UUID storeId, UUID id) {
        return jobs.findByIdAndStoreId(id, storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono makra"));
    }

    private void announce(AgentMacroJob job) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", job.getId());
        fields.put("status", job.getStatus().name());
        fields.put("kind", job.getKind().name());
        // Powiadomienie o makrze zleconym przez serwer dostaje osoba, która zapisała plik.
        fields.put("userId", job.getCreatedBy() == null ? null : job.getCreatedBy().getId());
        // Reszta jest do powiadomień o końcu makra dla pozostałych osób w sklepie.
        fields.put("title", job.getTitle());
        fields.put("computer", job.getAgentId() == null ? null
                : agents.findById(job.getAgentId()).map(AgentMacroService::label).orElse(null));
        if (job.getResultMessage() != null) {
            String message = job.getResultMessage();
            fields.put("message", message.length() > MESSAGE_LENGTH ? message.substring(0, MESSAGE_LENGTH) + "…"
                    : message);
        }
        if (job.getStatus() == AgentMacroStatus.QUEUED && job.getRetryAt() != null) {
            fields.put("postponed", job.getWaitReason());
        }
        live.publish(job.getStore().getId(), "agent-macro", fields);
        if (job.getStatus().ended()) {
            events.publishEvent(new MacroEnded(job.getStore().getId(), job.getId(), job.getKind(), job.getStatus()));
        }
    }

    private static String requestedBy(AgentMacroJob job) {
        return job.getCreatedBy() == null ? SERVER_REQUEST : job.getCreatedBy().getDisplayName();
    }

    private AgentMacroApi.Job response(AgentMacroJob job) {
        String computer = job.getAgentId() == null ? null
                : agents.findById(job.getAgentId()).map(AgentMacroService::label).orElse(null);
        List<String> declined = job.getDeclinedAgentIds().stream()
                .map(id -> agents.findById(id).map(AgentMacroService::label).orElse("komputer")).toList();
        return new AgentMacroApi.Job(job.getId(), job.getKind().name(), job.getTitle(), job.getStatus().name(),
                computer, requestedBy(job), job.getCreatedAt(), job.getOfferedAt(),
                job.getStartedAt(), job.getFinishedAt(), countdown(job.getKind()), declined, job.getResultMessage(),
                job.getResultCodes(), job.getItemCount(), job.getReference(),
                position(job.getStore().getId(), job.getId(), job.getStatus()), job.getWaitReason());
    }

    private Integer position(UUID storeId, UUID id, AgentMacroStatus status) {
        if (status != AgentMacroStatus.QUEUED) {
            return null;
        }
        int index = jobs.findQueuedIds(storeId).indexOf(id);
        return index < 0 ? null : index + 1;
    }
}
