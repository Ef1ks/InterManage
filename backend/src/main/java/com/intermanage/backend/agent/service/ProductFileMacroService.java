package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.agent.component.ProductFileMacro;
import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.repository.AgentMacroJobRepository;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.entity.ProductFileExport;
import com.intermanage.backend.dataentry.service.DataEntryProductFileService;
import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.repository.StoreFileQueueRepository;
import com.intermanage.backend.store.service.StoreFileQueueService;

/**
 * Plik nowych i zmienionych artykułów, który agent właśnie zapisał w folderze sklepu, od razu wczytuje makro na
 * komputerze sklepu (wybór komputera, odliczanie i jedno makro naraz jak przy makrach z przeglądarki). Liczy się tylko
 * zapis agenta: plik wrzucony do folderu ręcznie albo zapisany z przeglądarki makra nie uruchamia.
 *
 * <p>Pliki zapisane w odstępie {@code app.agent.product-file-macro.delay} (np. dostawa z bazy: 538 i 88110) wczytuje
 * jedno makro, a zapisane w trakcie innego makra sklepu czekają na jego koniec, najwyżej {@link #MAX_WAIT}.
 */
@Service
public class ProductFileMacroService {

    private static final Logger log = LoggerFactory.getLogger(ProductFileMacroService.class);
    /** Dłużej czekający plik ktoś zwykle wczytał już ręcznie (najdłuższe makro, kody MEA, trwa do 6 godzin). */
    static final Duration MAX_WAIT = Duration.ofHours(7);

    private final AgentMacroService macros;
    private final AgentMacroJobRepository jobs;
    private final StoreAgentService agents;
    private final StoreFileQueueRepository queue;
    private final DataEntryProductFileService productFiles;
    private final LiveEvents live;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final Duration delay;

    public ProductFileMacroService(AgentMacroService macros, AgentMacroJobRepository jobs, StoreAgentService agents,
            StoreFileQueueRepository queue, DataEntryProductFileService productFiles, LiveEvents live,
            TransactionTemplate transaction,
            @Value("${app.agent.product-file-macro.enabled:true}") boolean enabled,
            @Value("${app.agent.product-file-macro.delay:PT10S}") Duration delay) {
        this.macros = macros;
        this.jobs = jobs;
        this.agents = agents;
        this.queue = queue;
        this.productFiles = productFiles;
        this.live = live;
        this.transaction = transaction;
        this.enabled = enabled;
        this.delay = delay;
    }

    /**
     * W transakcji zapisu: plik czeka na makro (zostaje w bazie, więc restart serwera go nie gubi). Sklep, w którym
     * żaden komputer nie ma jeszcze odtwarzacza z potrzebnymi poleceniami, wczytuje pliki jak dotąd, ręcznie.
     */
    @EventListener
    public void onSaved(StoreFileQueueService.FileSavedByAgent event) {
        if (!enabled || !DefaultFolders.PRODUCT_FILE.equals(event.folderKey())
                || !macros.anyPlayer(event.storeId(), ProductFileMacro.PLAYER_MINOR)) {
            return;
        }
        StoreFileQueueItem item = queue.findById(event.id()).orElseThrow();
        ProductFileExport export = productFiles.exported(event.storeId(), item.getFileName(), item.getContent())
                .orElse(null);
        if (export != null && ProductFileMacro.supplierCode(export.getSupplierCode()) != null) {
            queue.requestMacro(event.id(), export.getId());
        } else if (item.getCreatedBy() != null) {
            live.publish(event.storeId(), "agent-macro", Map.of("id", event.id(), "kind",
                    AgentMacroKind.PRODUCT_FILES.name(), "status", "NO_SUPPLIER", "userId", item.getCreatedBy().getId(),
                    "message", item.getFileName()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterSaved(StoreFileQueueService.FileSavedByAgent event) {
        if (enabled && DefaultFolders.PRODUCT_FILE.equals(event.folderKey())) {
            later(event.storeId());
        }
    }

    /** Koniec innego makra sklepu: pliki zapisane w jego trakcie. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterMacro(AgentMacroService.MacroEnded event) {
        if (enabled) {
            later(event.storeId());
        }
    }

    private void later(UUID storeId) {
        Thread.ofVirtual().name("product-file-macro-" + storeId).start(() -> {
            try {
                Thread.sleep(delay);
                transaction.executeWithoutResult(status -> dispatch(storeId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // W sklepie ruszyło właśnie inne makro: pliki dalej czekają i ruszą po jego końcu.
                log.info("Makro nowych artykułów sklepu {} jeszcze nie ruszyło: {}", storeId, e.getMessage());
            }
        });
    }

    private void dispatch(UUID storeId) {
        List<StoreFileQueueRepository.MacroFile> files = queue.lockWaitingForMacro(storeId,
                (int) MAX_WAIT.toMinutes());
        if (files.isEmpty()) {
            return;
        }
        run(storeId, files, files.getLast().getCreatedBy());
    }

    /** Ponowne wczytanie plików makra, które nie doszło do końca (np. żaden komputer nie był wolny). */
    public AgentMacroApi.Job retry(UUID storeId, UUID jobId, UUID userId) {
        return transaction.execute(status -> {
            AgentMacroJob job = jobs.findByIdAndStoreId(jobId, storeId)
                    .filter(found -> found.getKind() == AgentMacroKind.PRODUCT_FILES)
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono makra"));
            List<StoreFileQueueRepository.MacroFile> files = queue.findMacroFiles(jobId);
            if (files.isEmpty() || job.getStatus() == AgentMacroStatus.OFFERED
                    || job.getStatus() == AgentMacroStatus.RUNNING || job.getStatus() == AgentMacroStatus.DONE) {
                throw ApiException.conflict("Te pliki są już wczytane albo wczytuje je inne makro");
            }
            return run(storeId, files, userId);
        });
    }

    private AgentMacroApi.Job run(UUID storeId, List<StoreFileQueueRepository.MacroFile> files, UUID userId) {
        String folder = agents.folders(storeId).get(DefaultFolders.PRODUCT_FILE);
        Map<UUID, ProductFileExport> exports = productFiles.exported(files.stream()
                .map(StoreFileQueueRepository.MacroFile::getExportId).toList()).stream()
                .collect(Collectors.toMap(ProductFileExport::getId, Function.identity()));
        // Ten sam plik zapisany ponownie leży w folderze raz, w nowszej wersji.
        Map<String, ProductFileMacro.File> byPath = new LinkedHashMap<>();
        for (StoreFileQueueRepository.MacroFile file : files) {
            String path = ProductFileMacro.path(folder, file.getFileName());
            ProductFileExport export = exports.get(file.getExportId());
            byPath.put(path, new ProductFileMacro.File(path, export.getSupplierCode(), export.getArticles()));
        }
        AgentMacroApi.Job job = macros.productFiles(storeId, List.copyOf(byPath.values()), userId);
        queue.assignMacro(files.stream().map(StoreFileQueueRepository.MacroFile::getId).toList(), job.id());
        return job;
    }
}
