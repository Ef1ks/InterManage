package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.repository.AgentMacroJobRepository;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.PriceControlMacro;
import com.intermanage.backend.dataentry.entity.DataEntryPriceControlFile;
import com.intermanage.backend.dataentry.repository.DataEntryPriceControlFileRepository;
import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.repository.StoreFileQueueRepository;
import com.intermanage.backend.store.service.StoreFileQueueService;

/**
 * Zmiana cen, która pojawiła się w folderze sklepu (zapisał ją agent albo główny agent zobaczył ją w folderze), sama
 * wchodzi do Mercalys: makro idzie na komputer preferowany (sklep bez preferowanego: dowolny wolny, jak inne makra), okno
 * agenta pokazuje zapowiedź i rusza po {@link AgentMacroService#PRICE_CONTROL_COUNTDOWN_SECONDS} s bez ruchu.
 *
 * <p>Gdy komputer jest wyłączony, zablokowany albo zajęty innym makrem, zmiana czeka w bazie, najwyżej
 * {@link DataEntryPriceControlFile#MACRO_WAIT}; po „Anuluj” kolejna próba jest po {@link #RETRY_AFTER}. Wczytanie
 * potwierdza jak dotąd folder (końcówka .TraiteN), więc zmiana wprowadzona w tym czasie ręcznie wypada z kolejki.
 */
@Service
public class PriceControlMacroService {

    private static final Logger log = LoggerFactory.getLogger(PriceControlMacroService.class);
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    /** Plik zobaczony w folderze liczy się tylko świeży: starszy mógł zostać celowo albo ma już nowsze ceny. */
    private static final Duration FRESH_IN_FOLDER = Duration.ofMinutes(30);

    private final AgentMacroService macros;
    private final AgentMacroJobRepository jobs;
    private final StoreAgentService agents;
    private final StoreAgentFeatureService features;
    private final StoreFileQueueRepository queue;
    private final DataEntryPriceControlFileRepository files;
    private final LiveEvents live;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final Duration delay;

    public PriceControlMacroService(AgentMacroService macros, AgentMacroJobRepository jobs, StoreAgentService agents,
            StoreAgentFeatureService features,
            StoreFileQueueRepository queue, DataEntryPriceControlFileRepository files, LiveEvents live,
            TransactionTemplate transaction,
            @Value("${app.agent.price-control-macro.enabled:true}") boolean enabled,
            @Value("${app.agent.price-control-macro.delay:PT2S}") Duration delay) {
        this.macros = macros;
        this.jobs = jobs;
        this.agents = agents;
        this.features = features;
        this.queue = queue;
        this.files = files;
        this.live = live;
        this.transaction = transaction;
        this.enabled = enabled;
        this.delay = delay;
    }

    /** W transakcji zapisu agenta: zmiana cen czeka na makro (zostaje w bazie, więc restart serwera jej nie gubi). */
    @EventListener
    public void onSaved(StoreFileQueueService.FileSavedByAgent event) {
        if (!enabled || !DefaultFolders.PROTO.equals(event.folderKey())
                || !on(event.storeId())) {
            return;
        }
        StoreFileQueueItem item = queue.findById(event.id()).orElseThrow();
        if (PriceControlMacro.time(item.getFileName()) == null) {
            return; // protokół, etykiety, zamówienie
        }
        files.findByStoreIdAndFileName(event.storeId(), item.getFileName())
                .forEach(DataEntryPriceControlFile::requestMacro);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterSaved(StoreFileQueueService.FileSavedByAgent event) {
        if (enabled && DefaultFolders.PROTO.equals(event.folderKey())) {
            later(event.storeId());
        }
    }

    /**
     * Główny agent widzi w folderze pliki z .traite0 (nazwy bez końcówki, małymi literami): zmiana zapisana z
     * przeglądarki albo ręcznie też sama wchodzi do Mercalys. Przegląd folderu powtarza nazwy, więc liczą się tylko
     * świeże i jeszcze niezlecone.
     */
    public void seenWaiting(UUID storeId, Collection<String> baseNames) {
        Instant oldest = Instant.now().minus(FRESH_IN_FOLDER);
        Set<String> fresh = baseNames.stream().filter(name -> {
            LocalDateTime time = PriceControlMacro.time(name);
            return time != null && time.atZone(StoreTime.ZONE).toInstant().isAfter(oldest);
        }).collect(Collectors.toSet());
        if (!enabled || fresh.isEmpty() || !agents.enabled(storeId) || !on(storeId)) {
            return;
        }
        Boolean requested = transaction.execute(status -> {
            List<UUID> ids = files.findPending(storeId).stream()
                    .filter(file -> file.getMacroRequestedAt() == null && fresh.contains(baseName(file.getFileName())))
                    .map(DataEntryPriceControlFileRepository.Summary::getId).toList();
            if (!ids.isEmpty()) {
                files.findByStoreIdAndIdIn(storeId, ids).forEach(DataEntryPriceControlFile::requestMacro);
            }
            return !ids.isEmpty();
        });
        if (Boolean.TRUE.equals(requested)) {
            later(storeId);
        }
    }

    /** Koniec makra sklepu: niepodjęte zmiany cen wracają do czekania, a czekające mogą ruszyć. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterMacro(AgentMacroService.MacroEnded event) {
        if (!enabled) {
            return;
        }
        if (event.kind() != AgentMacroKind.PRICE_CONTROL || event.status() != AgentMacroStatus.NO_COMPUTER) {
            later(event.storeId());
            return;
        }
        Thread.ofVirtual().name("price-control-put-off-" + event.storeId()).start(() -> {
            try {
                transaction.executeWithoutResult(status -> {
                    Instant retryAt = Instant.now().plus(RETRY_AFTER);
                    files.findByMacroJobId(event.jobId()).forEach(file -> file.putOffMacro(retryAt));
                    if (!on(event.storeId())) {
                        files.cancelWaitingForMacro(event.storeId());
                    }
                });
                live.publish(event.storeId(), "price-control", Map.of("action", "macro"));
            } catch (RuntimeException e) {
                log.warn("Zmiany cen sklepu {} nie wróciły do czekania na makro", event.storeId(), e);
            }
        });
    }

    /** Automat wyłączony w sklepie: czekające zmiany zostają do wprowadzenia ręcznie. */
    @EventListener
    public void onSwitchedOff(StoreAgentFeatureService.SwitchedOff event) {
        if (event.feature() == AgentFeature.PRICE_CONTROL_MACRO) {
            files.cancelWaitingForMacro(event.storeId());
            live.publish(event.storeId(), "price-control", Map.of("action", "macro"));
        }
    }

    /** Zmiany, które czekają na komputer (włączony, odblokowany, bez innego makra). */
    @Scheduled(fixedDelayString = "${app.agent.price-control-macro.check-interval:PT20S}", initialDelayString = "PT1M")
    public void dispatchWaiting() {
        if (enabled) {
            Instant now = Instant.now();
            files.findStoresWaitingForMacro(now.minus(DataEntryPriceControlFile.MACRO_WAIT), now)
                    .forEach(this::tryDispatch);
        }
    }

    private boolean on(UUID storeId) {
        return features.isOn(storeId, AgentFeature.PRICE_CONTROL_MACRO);
    }

    private void later(UUID storeId) {
        Thread.ofVirtual().name("price-control-macro-" + storeId).start(() -> {
            try {
                Thread.sleep(delay);
                tryDispatch(storeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void tryDispatch(UUID storeId) {
        try {
            transaction.executeWithoutResult(status -> dispatch(storeId));
        } catch (ApiException e) {
            // W sklepie trwa inne makro: zmiany dalej czekają.
            log.debug("Makro zmian cen sklepu {} jeszcze nie ruszyło: {}", storeId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Makro zmian cen sklepu {}", storeId, e);
        }
    }

    private void dispatch(UUID storeId) {
        Instant now = Instant.now();
        // Najpierw komputer: w nocy zmiany czekają bez zakładania zleceń, których nikt nie weźmie.
        if (!on(storeId) || !macros.canRun(storeId, AgentMacroKind.PRICE_CONTROL)) {
            return;
        }
        List<DataEntryPriceControlFile> waiting = files.lockWaitingForMacro(storeId,
                now.minus(DataEntryPriceControlFile.MACRO_WAIT), now);
        waiting.stream().map(DataEntryPriceControlFile::getCreatedBy).filter(Objects::nonNull)
                .reduce((first, last) -> last).map(AppUser::getId)
                .ifPresent(userId -> run(storeId, waiting, userId));
    }

    /** Ponowienie makra, które się nie udało: tylko zmiany, których program sklepu jeszcze nie wczytał. */
    public AgentMacroApi.Job retry(UUID storeId, UUID jobId, UUID userId) {
        return transaction.execute(status -> {
            AgentMacroJob job = jobs.findByIdAndStoreId(jobId, storeId)
                    .filter(found -> found.getKind() == AgentMacroKind.PRICE_CONTROL)
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono makra"));
            List<DataEntryPriceControlFile> left = files.findByMacroJobId(jobId).stream()
                    .filter(file -> !file.isLoaded() && !file.isDeleted())
                    .sorted(Comparator.comparing(DataEntryPriceControlFile::getFileName)).toList();
            if (job.getStatus() == AgentMacroStatus.OFFERED || job.getStatus() == AgentMacroStatus.RUNNING
                    || left.isEmpty()) {
                throw ApiException.conflict("Te zmiany cen są już wprowadzone albo wprowadza je inne makro");
            }
            return run(storeId, left, userId);
        });
    }

    private AgentMacroApi.Job run(UUID storeId, List<DataEntryPriceControlFile> list, UUID userId) {
        AgentMacroApi.Job job = macros.priceControl(storeId, list.stream()
                .map(file -> new PriceControlMacro.File(file.getFileName(), file.getLineCount(), title(file))).toList(),
                userId);
        list.forEach(file -> file.assignMacro(job.id()));
        live.publish(storeId, "price-control", Map.of("action", "macro"));
        return job;
    }

    private static String title(DataEntryPriceControlFile file) {
        String supplier = file.getSupplierName() != null ? file.getSupplierName()
                : file.getSupplierCode() != null ? file.getSupplierCode() : "bez dostawcy";
        return file.getInvoiceNumber() == null || file.getInvoiceNumber().isBlank() ? supplier
                : supplier + " · " + file.getInvoiceNumber();
    }

    private static String baseName(String fileName) {
        return fileName.replaceFirst("(?i)\\.traite\\d+$", "").toLowerCase(Locale.ROOT);
    }
}
