package com.intermanage.backend.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.agent.dto.AgentApi;
import com.intermanage.backend.agent.dto.AgentReportFileResponse;
import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.agent.entity.AgentReportFile;
import com.intermanage.backend.agent.entity.StoreAgentSettings;
import com.intermanage.backend.agent.mapper.StoreAgentMapper;
import com.intermanage.backend.agent.repository.AgentReportFileRepository;
import com.intermanage.backend.agent.repository.StoreAgentSettingsRepository;
import com.intermanage.backend.common.component.ChunkedUploads;
import com.intermanage.backend.common.component.ImportSlots;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.importguide.entity.ImportReportKind;
import com.intermanage.backend.importguide.service.ImportHistoryService;
import com.intermanage.backend.importguide.service.ImportHistoryService.ImportedFile;
import com.intermanage.backend.pricecheck.service.CadencierService;
import com.intermanage.backend.product.dto.ProductImportJobResponse;
import com.intermanage.backend.product.service.ProductCodeImportService;
import com.intermanage.backend.product.service.ProductImportService;
import com.intermanage.backend.promotion.service.PromotionImportService;
import com.intermanage.backend.sales.service.SalesImportService;
import com.intermanage.backend.store.service.StoreFileLocationService;
import com.intermanage.backend.supplier.service.SupplierService;

/**
 * Raporty Importu danych przez agenta sklepu (zamiast „Odśwież dane” w przeglądarce):
 * <ul>
 * <li>agent pilnuje folderu każdego codziennego raportu ({@link #watches}: folder z ustawień importu, bez niego folder
 * ścieżki zapisu makra; nazwa z maską i końcówką raportu), rodzaj folderu {@code IMPORT_<raport>};
 * <li>z jego zdarzeń serwer wybiera pliki do wgrania ({@link #handle}) tak jak przeglądarka: raport z okresem
 * (sprzedaż, dokumenty) każdy niewgrany plik od najstarszego, pozostałe tylko najnowszy i tylko nowszy niż ostatnie
 * wgranie; plik, który już był wysłany (także nieudany), nie wraca, dopóki się nie zmieni;
 * <li>agent wysyła plik w kawałkach ({@link ChunkedUploads}, wolne łącze sklepu), a import idzie w tle tą samą
 * logiką co z przeglądarki, w sklepie po kolei;
 * <li>wgrany raport agent usuwa z folderu następnego dnia ({@code deletes} w odpowiedzi na zdarzenia), tylko gdy plik
 * ma dalej tę samą datę zmiany.
 * </ul>
 */
@Service
public class AgentReportService {

    private static final Logger log = LoggerFactory.getLogger(AgentReportService.class);

    public static final String PREFIX = "IMPORT_";

    /** Codzienne raporty w kolejności odświeżania (jak {@code DAILY_REPORTS} we froncie). */
    static final List<ImportReportKind> DAILY = List.of(ImportReportKind.PRODUCTS, ImportReportKind.PRODUCT_CODES,
            ImportReportKind.PROMOTIONS, ImportReportKind.CADENCIER, ImportReportKind.SUPPLIERS,
            ImportReportKind.SALES, ImportReportKind.DOCUMENTS);

    /** Raporty z okresem: makro zapisuje zaległość w częściach, więc wgrywamy każdy niewgrany plik. */
    private static final Set<ImportReportKind> PERIOD = Set.of(ImportReportKind.SALES, ImportReportKind.DOCUMENTS);

    /** Tyle ostatnich wgrań raportu sprawdzamy; plik starszy niż najstarsze z pełnej listy uznajemy za wgrany. */
    private static final int HISTORY_SIZE = 200;

    /** Nieudana próba z błędem serwera wraca po tej przerwie. */
    private static final Duration RETRY_AFTER = Duration.ofMinutes(15);

    /** Wysyłka bez nowego kawałka dłużej niż tyle jest porzucona (agent wyśle plik od nowa). */
    private static final Duration UPLOAD_STALE = Duration.ofMinutes(20);

    private static final Duration PRODUCT_JOB_POLL = Duration.ofSeconds(2);

    private final AgentReportFileRepository files;
    private final StoreAgentSettingsRepository settings;
    private final StoreFileLocationService locations;
    private final ImportHistoryService history;
    private final ChunkedUploads uploads;
    private final ImportSlots slots;
    private final LiveEvents live;
    private final StoreAgentMapper mapper;
    private final TransactionTemplate transaction;
    private final ProductImportService products;
    private final ProductCodeImportService productCodes;
    private final PromotionImportService promotions;
    private final SalesImportService sales;
    private final DocumentService documents;
    private final CadencierService cadencier;
    private final SupplierService suppliers;
    private final StoreAgentFeatureService features;

    /**
     * Start tego procesu: plik odrzucony przez wcześniejszą wersję serwera (np. przed poprawką odczytu raportu) dostaje
     * po wdrożeniu jedną nową próbę; odrzucony znowu czeka na zmianę pliku.
     */
    private final Instant startedAt = Instant.now();

    /** Próby w toku w tym procesie: po restarcie serwera wiersz UPLOADING / IMPORTING nie blokuje pliku. */
    private final Map<UUID, Instant> active = new ConcurrentHashMap<>();
    /** Wysyłki kawałków: id próby → id wysyłki w {@link ChunkedUploads}. */
    private final Map<UUID, UUID> uploadIds = new ConcurrentHashMap<>();
    /** Importy sklepu idą po kolei (części okresu od najstarszej). */
    private final Map<UUID, ReentrantLock> storeLocks = new ConcurrentHashMap<>();

    public AgentReportService(AgentReportFileRepository files, StoreAgentSettingsRepository settings,
            StoreFileLocationService locations, ImportHistoryService history, ChunkedUploads uploads,
            LiveEvents live, StoreAgentMapper mapper, TransactionTemplate transaction, ProductImportService products,
            ProductCodeImportService productCodes, PromotionImportService promotions, SalesImportService sales,
            DocumentService documents, CadencierService cadencier, SupplierService suppliers,
            ImportSlots slots, StoreAgentFeatureService features) {
        this.features = features;
        this.slots = slots;
        this.files = files;
        this.settings = settings;
        this.locations = locations;
        this.history = history;
        this.uploads = uploads;
        this.live = live;
        this.mapper = mapper;
        this.transaction = transaction;
        this.products = products;
        this.productCodes = productCodes;
        this.promotions = promotions;
        this.sales = sales;
        this.documents = documents;
        this.cadencier = cadencier;
        this.suppliers = suppliers;
    }

    // --- Konfiguracja --------------------------------------------------------------------------------------------

    /**
     * Foldery raportów do pilnowania, tylko gdy sklep pracuje przez agenta. Raport bez folderu w ustawieniach importu
     * (i bez ścieżki zapisu makra) zostaje przy przeglądarce.
     */
    public List<AgentApi.Watch> watches(UUID storeId) {
        if (!enabled(storeId)) {
            return List.of();
        }
        Map<String, String> values = locations.get(storeId).locations();
        List<AgentApi.Watch> watches = new ArrayList<>();
        for (ImportReportKind kind : DAILY) {
            String folder = folderOf(values, kind);
            if (folder != null) {
                watches.add(new AgentApi.Watch(PREFIX + kind.name(), folder, false,
                        pattern(values.get("import:" + kind.name() + ":mask"), kind),
                        freshPattern(values.get("import:" + kind.name() + ":path"))));
            }
        }
        return watches;
    }

    /** Folder raportu: ścieżka folderu z ustawień importu, bez niej folder pliku, który zapisuje makro. */
    static String folderOf(Map<String, String> values, ImportReportKind kind) {
        String folder = values.get("folder:import:" + kind.name());
        if (isFolder(folder)) {
            return folder.strip().replaceAll("\\\\+$", "");
        }
        String path = values.get("import:" + kind.name() + ":path");
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.strip();
        int slash = trimmed.lastIndexOf('\\');
        String parent = slash > 0 ? trimmed.substring(0, slash) : null;
        if (parent != null && parent.matches("^[A-Za-z]:$")) {
            parent = parent + "\\";
        }
        return isFolder(parent) ? parent : null;
    }

    private static boolean isFolder(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String path = value.strip();
        return !path.contains("…") && (path.matches("^[A-Za-z]:\\\\.*") || path.startsWith("\\\\"));
    }

    /**
     * Wyrażenie na nazwę pliku: maska jak w Eksploratorze ({@code *}, {@code ?}, bez wielkości liter; bez gwiazdki
     * pasuje też nazwa z dopiskiem), końcówka raportu, bez plików tymczasowych Excela ({@code ~$…}). Ta sama zasada co
     * {@code maskPattern} we froncie.
     */
    static String pattern(String mask, ImportReportKind kind) {
        String value = mask == null ? "" : mask.strip();
        StringBuilder source = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '*') {
                source.append(".*");
            } else if (c == '?') {
                source.append('.');
            } else if (".+^${}()|[]\\".indexOf(c) >= 0) {
                source.append('\\').append(c);
            } else {
                source.append(c);
            }
        }
        if (!value.contains("*")) {
            source.append(".*");
        }
        String extensions = kind == ImportReportKind.PROMOTIONS ? "pdf" : "xlsx|xlsm";
        return "^(?!~\\$)" + source + "\\.(?:" + extensions + ")$";
    }

    /**
     * Nazwa pliku, który makro zapisuje na końcu (ścieżka z „Zapis z Mercalys”; część z najnowszym okresem zawsze
     * idzie pod tę nazwę, wcześniejsze dostają „ N z M”). Bez ścieżki null: aktualność po każdym pliku z folderu.
     */
    static String freshPattern(String macroPath) {
        if (macroPath == null || macroPath.isBlank()) {
            return null;
        }
        String path = macroPath.strip();
        String name = path.substring(path.lastIndexOf('\\') + 1);
        if (name.isEmpty()) {
            return null;
        }
        // Bez \Q…\E: wyrażenie czyta agent w .NET.
        StringBuilder source = new StringBuilder("^");
        for (char c : name.toCharArray()) {
            if (".+*?^${}()|[]\\#".indexOf(c) >= 0) {
                source.append('\\');
            }
            source.append(c);
        }
        return source.append('$').toString();
    }

    private boolean enabled(UUID storeId) {
        return settings.findById(storeId).map(StoreAgentSettings::isEnabled).orElse(false)
                && features.isOn(storeId, AgentFeature.REPORTS);
    }

    public static ImportReportKind kindOf(String folderKey) {
        if (folderKey == null || !folderKey.toUpperCase(Locale.ROOT).startsWith(PREFIX)) {
            return null;
        }
        try {
            ImportReportKind kind = ImportReportKind.valueOf(folderKey.substring(PREFIX.length()).toUpperCase(Locale.ROOT));
            return DAILY.contains(kind) ? kind : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Zdarzenia z folderów ----------------------------------------------------------------------------------

    /** Plik raportu widziany w folderze. */
    record SeenFile(String name, Instant modified, long size) {
    }

    /** Pliki do wysłania i wgrane pliki do usunięcia z folderu (zdarzenia {@code IMPORT_…}). */
    public AgentApi.EventsResponse handle(UUID storeId, List<AgentApi.Event> events) {
        if (!enabled(storeId)) {
            return new AgentApi.EventsResponse(List.of(), List.of());
        }
        Map<String, String> values = locations.get(storeId).locations();
        Map<ImportReportKind, java.util.regex.Pattern> patterns = new HashMap<>();
        Map<ImportReportKind, Map<String, SeenFile>> byKind = new LinkedHashMap<>();
        for (AgentApi.Event event : events) {
            ImportReportKind kind = kindOf(event.folderKey());
            if (kind == null || "DELETED".equalsIgnoreCase(event.kind()) || event.modified() == null) {
                continue;
            }
            // Ta sama maska co w konfiguracji agenta: plik tymczasowy Excela albo inny plik w folderze nie wchodzi.
            java.util.regex.Pattern pattern = patterns.computeIfAbsent(kind, k -> java.util.regex.Pattern.compile(
                    pattern(values.get("import:" + k.name() + ":mask"), k),
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE));
            if (!pattern.matcher(event.fileName()).matches()) {
                continue;
            }
            byKind.computeIfAbsent(kind, ignored -> new LinkedHashMap<>()).put(
                    event.fileName().toLowerCase(Locale.ROOT),
                    new SeenFile(event.fileName(), event.modified(), event.size() == null ? 0 : event.size()));
        }
        List<AgentApi.FileRequest> requests = new ArrayList<>();
        List<AgentApi.FileDelete> deletes = new ArrayList<>();
        Instant todayStart = LocalDate.now(StoreTime.ZONE).atStartOfDay(StoreTime.ZONE).toInstant();
        Instant now = Instant.now();
        for (ImportReportKind kind : DAILY) {
            Map<String, SeenFile> seen = byKind.get(kind);
            if (seen == null) {
                continue;
            }
            Decision decision = decide(storeId, kind, List.copyOf(seen.values()), todayStart, now);
            decision.upload().forEach(file -> requests.add(new AgentApi.FileRequest(PREFIX + kind.name(), file.name())));
            decision.delete().forEach(file -> deletes.add(
                    new AgentApi.FileDelete(PREFIX + kind.name(), file.name(), file.modified())));
        }
        return new AgentApi.EventsResponse(requests,
                features.isOn(storeId, AgentFeature.REPORT_CLEANUP) ? deletes : List.of());
    }

    record Decision(List<SeenFile> upload, List<SeenFile> delete) {
    }

    private Decision decide(UUID storeId, ImportReportKind kind, List<SeenFile> seen, Instant todayStart,
            Instant now) {
        List<ImportedFile> imports = history.recent(storeId, kind, HISTORY_SIZE);
        Map<String, List<AgentReportFile>> attempts = files.findByNames(storeId, kind.name(),
                seen.stream().map(file -> file.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()))
                .stream().collect(Collectors.groupingBy(file -> file.getFileName().toLowerCase(Locale.ROOT),
                        HashMap::new, Collectors.toList()));
        return decide(kind, seen, imports, attempts, todayStart, now);
    }

    /** Decyzja bez bazy (testy): co wysłać, co usunąć. */
    Decision decide(ImportReportKind kind, List<SeenFile> seen, List<ImportedFile> imports,
            Map<String, List<AgentReportFile>> attempts, Instant todayStart, Instant now) {
        Instant lastImported = imports.stream().map(ImportedFile::importedAt).max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        Instant oldestVisible = imports.size() >= HISTORY_SIZE
                ? imports.stream().map(ImportedFile::importedAt).min(Comparator.naturalOrder()).orElse(null)
                : null;
        List<SeenFile> candidates = new ArrayList<>();
        List<SeenFile> delete = new ArrayList<>();
        for (SeenFile file : seen) {
            // Excel zakłada pusty plik i dopiero go zapisuje: pustego nie bierzemy, pójdzie po zmianie.
            if (file.size() <= 0) {
                continue;
            }
            List<AgentReportFile> tries = attempts.getOrDefault(file.name().toLowerCase(Locale.ROOT), List.of())
                    .stream().filter(attempt -> sameFile(attempt, file)).toList();
            Instant importedAt = importedAt(file, imports, tries);
            if (importedAt != null) {
                // Wgrany przed dzisiejszym dniem: następnego dnia plik idzie z folderu.
                if (importedAt.isBefore(todayStart) && file.modified().isBefore(todayStart)) {
                    delete.add(file);
                }
                continue;
            }
            if (blocked(tries, now)) {
                continue;
            }
            if (PERIOD.contains(kind)) {
                if (oldestVisible != null && !file.modified().isAfter(oldestVisible)) {
                    continue;
                }
                candidates.add(file);
            } else if (file.modified().isAfter(lastImported)) {
                candidates.add(file);
            }
        }
        candidates.sort(Comparator.comparing(SeenFile::modified));
        if (!PERIOD.contains(kind) && candidates.size() > 1) {
            candidates = List.of(candidates.get(candidates.size() - 1));
        }
        return new Decision(candidates, delete);
    }

    /** Kiedy plik (ta nazwa i data zmiany) został wgrany: przez agenta albo z przeglądarki po swojej zmianie. */
    private static Instant importedAt(SeenFile file, List<ImportedFile> imports, List<AgentReportFile> tries) {
        Instant byAgent = tries.stream().filter(attempt -> attempt.getStatus() == AgentReportFile.Status.IMPORTED)
                .map(AgentReportFile::getFinishedAt).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        if (byAgent != null) {
            return byAgent;
        }
        return imports.stream()
                .filter(item -> item.fileName() != null && item.fileName().equalsIgnoreCase(file.name())
                        && !item.importedAt().isBefore(file.modified()))
                .map(ImportedFile::importedAt).min(Comparator.naturalOrder()).orElse(null);
    }

    /** Próba tego pliku w toku albo nieudana (bez ponawiania albo przed końcem przerwy): nie prosimy o niego. */
    private boolean blocked(List<AgentReportFile> tries, Instant now) {
        for (AgentReportFile attempt : tries) {
            switch (attempt.getStatus()) {
                case UPLOADING, IMPORTING -> {
                    if (inProgress(attempt)) {
                        return true;
                    }
                }
                case FAILED -> {
                    if (attempt.getFinishedAt() == null) {
                        return true;
                    }
                    if (!attempt.isRetryable() && attempt.getFinishedAt().isAfter(startedAt)) {
                        return true;
                    }
                    if (attempt.isRetryable() && attempt.getFinishedAt().isAfter(now.minus(RETRY_AFTER))) {
                        return true;
                    }
                }
                case IMPORTED -> {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Próba naprawdę w toku w tym procesie: import trwa, a wysyłka dostała kawałek w ostatnich minutach. Po restarcie
     * serwera albo zerwanej wysyłce plik wraca do wgrania.
     */
    private boolean inProgress(AgentReportFile attempt) {
        Instant touched = active.get(attempt.getId());
        if (touched == null) {
            return false;
        }
        return attempt.getStatus() == AgentReportFile.Status.IMPORTING
                || touched.isAfter(Instant.now().minus(UPLOAD_STALE));
    }

    /**
     * Próba tego samego pliku: data zmiany i rozmiar. Sama data nie wystarcza, bo plik wysłany w chwili, gdy Excel go
     * zakładał (0 bajtów), ma datę o ułamek sekundy wcześniejszą od gotowego i jego odrzucenie blokowało gotowy plik.
     */
    private static boolean sameFile(AgentReportFile attempt, SeenFile file) {
        return sameModified(attempt.getFileModified(), file.modified()) && attempt.getFileSize() == file.size();
    }

    /** Daty zmiany z dokładnością do sekundy (dysk sieciowy i serializacja potrafią uciąć ułamki). */
    private static boolean sameModified(Instant a, Instant b) {
        return Math.abs(Duration.between(a, b).toMillis()) < 1000;
    }

    // --- Wysyłka i import ----------------------------------------------------------------------------------------

    /** Agent zaczyna wysyłać plik, o który poprosił serwer; plik, którego serwer już nie chce, daje 409. */
    public AgentApi.ReportUpload start(StoreAgentService.AgentContext agent, AgentApi.ReportStart request) {
        ImportReportKind kind = kindOf(request.folderKey());
        if (kind == null) {
            throw ApiException.badRequest("Nieznany rodzaj raportu");
        }
        if (!features.isOn(agent.storeId(), AgentFeature.REPORTS)) {
            throw ApiException.conflict("Wgrywanie raportów przez agenta jest w tym sklepie wyłączone");
        }
        String fileName = java.nio.file.Path.of(request.fileName()).getFileName().toString();
        if (!fileName.matches("(?i)" + pattern("", kind))) {
            throw ApiException.badRequest("Plik " + fileName + " nie jest raportem " + kind.name());
        }
        Instant todayStart = LocalDate.now(StoreTime.ZONE).atStartOfDay(StoreTime.ZONE).toInstant();
        Decision decision = decide(agent.storeId(), kind,
                List.of(new SeenFile(fileName, request.modified(), request.size())), todayStart, Instant.now());
        if (decision.upload().isEmpty()) {
            throw ApiException.conflict("Ten plik jest już wgrany albo w trakcie wgrywania");
        }
        AgentReportFile attempt = transaction.execute(status -> files.save(new AgentReportFile(agent.storeId(),
                agent.agentId(), kind.name(), fileName, request.modified(), request.size())));
        active.put(attempt.getId(), Instant.now());
        try {
            uploadIds.put(attempt.getId(), uploads.start(owner(agent), fileName, request.size()));
        } catch (ApiException e) {
            finish(attempt.getId(), e.getMessage(), false);
            throw e;
        }
        publish(agent.storeId(), attempt.getId(), kind, fileName, "uploading", null);
        return new AgentApi.ReportUpload(attempt.getId(), ChunkedUploads.CHUNK_SIZE);
    }

    public void chunk(StoreAgentService.AgentContext agent, UUID id, int index, byte[] bytes) {
        UUID uploadId = uploadIds.get(id);
        if (uploadId == null) {
            throw ApiException.notFound("Nie znaleziono wysyłanego raportu. Wyślij go ponownie.");
        }
        active.put(id, Instant.now());
        uploads.append(owner(agent), uploadId, index, bytes);
    }

    /** Cały plik dotarł: import idzie w tle, w sklepie po kolei. */
    public void finish(StoreAgentService.AgentContext agent, UUID id) {
        UUID uploadId = uploadIds.remove(id);
        if (uploadId == null) {
            throw ApiException.notFound("Nie znaleziono wysyłanego raportu. Wyślij go ponownie.");
        }
        ChunkedUploads.UploadedFile file;
        try {
            file = uploads.take(owner(agent), uploadId);
        } catch (ApiException e) {
            finish(id, e.getMessage(), true);
            throw e;
        }
        AgentReportFile attempt = transaction.execute(status -> {
            AgentReportFile found = files.findById(id).orElseThrow();
            found.importing();
            return found;
        });
        ImportReportKind kind = ImportReportKind.valueOf(attempt.getKind());
        publish(attempt.getStoreId(), id, kind, attempt.getFileName(), "importing", null);
        Thread.ofVirtual().name("agent-report-" + attempt.getStoreId()).start(() -> run(attempt, kind, file));
    }

    /** Agent usunął wgrany raport z folderu. */
    public void removed(StoreAgentService.AgentContext agent, AgentApi.ReportRemoved request) {
        ImportReportKind kind = kindOf(request.folderKey());
        if (kind == null) {
            return;
        }
        transaction.executeWithoutResult(status -> files.findByNames(agent.storeId(), kind.name(),
                Set.of(request.fileName().toLowerCase(Locale.ROOT))).stream()
                .filter(attempt -> sameModified(attempt.getFileModified(), request.modified()))
                .forEach(AgentReportFile::removedFromDisk));
        log.info("Agent sklepu {} usunął wgrany raport {} z folderu", agent.storeNumber(), request.fileName());
    }

    private void run(AgentReportFile attempt, ImportReportKind kind, ChunkedUploads.UploadedFile file) {
        ReentrantLock lock = storeLocks.computeIfAbsent(attempt.getStoreId(), ignored -> new ReentrantLock());
        lock.lock();
        try (file) {
            importFile(attempt.getStoreId(), kind, file);
            finish(attempt.getId(), null, false);
            publish(attempt.getStoreId(), attempt.getId(), kind, attempt.getFileName(), "imported", null);
            log.info("Agent wgrał raport {} ({}) do sklepu {}", attempt.getFileName(), kind, attempt.getStoreId());
        } catch (ApiException e) {
            // Błąd serwera albo trwający import kartoteki z przeglądarki: plik wróci po przerwie. Błąd pliku (inny
            // sklep, starszy raport, zły układ) nie wraca, dopóki plik się nie zmieni.
            boolean retry = e.getStatus().is5xxServerError()
                    || (e.getMessage() != null && e.getMessage().startsWith("Trwa import"));
            String message = withRowErrors(e);
            if (!retry) {
                log.warn("Agent: raport {} sklepu {} odrzucony: {}", attempt.getFileName(), attempt.getStoreId(),
                        message);
            }
            finish(attempt.getId(), message, retry);
            publish(attempt.getStoreId(), attempt.getId(), kind, attempt.getFileName(), "failed", message);
        } catch (RuntimeException e) {
            log.error("Import raportu {} od agenta do sklepu {} nie powiódł się", attempt.getFileName(),
                    attempt.getStoreId(), e);
            String message = "Import nie powiódł się z powodu błędu serwera. Spróbuję ponownie za kwadrans.";
            finish(attempt.getId(), message, true);
            publish(attempt.getStoreId(), attempt.getId(), kind, attempt.getFileName(), "failed", message);
        } finally {
            lock.unlock();
        }
    }

    /** Ten sam import co wgranie z przeglądarki, bez konta (wgrał agent). */
    private void importFile(UUID storeId, ImportReportKind kind, MultipartFile file) {
        if (kind == ImportReportKind.PRODUCTS) {
            // Kartoteka bierze miejsce sama ({@code ProductImportJobs}); drugie tutaj by się z nim zakleszczyło.
            waitForProducts(storeId, products.startImport(storeId, file, null));
            return;
        }
        slots.call(kind + " sklepu " + storeId, () -> { }, () -> {
            importNow(storeId, kind, file);
            return null;
        });
    }

    private void importNow(UUID storeId, ImportReportKind kind, MultipartFile file) {
        switch (kind) {
            case PRODUCT_CODES -> productCodes.importReport(storeId, file, null);
            case PROMOTIONS -> promotions.importReport(storeId, file, null);
            case SALES -> sales.importReport(storeId, file, null);
            case DOCUMENTS -> documents.importReport(storeId, file, null);
            case CADENCIER -> cadencier.importReport(storeId, file, null);
            case SUPPLIERS -> suppliers.importReport(storeId, file, null);
            default -> throw ApiException.badRequest("Tego raportu agent nie wgrywa");
        }
    }

    /** Kartoteka importuje się w tle ({@code ProductImportJobs}); czekamy na wynik, żeby zapisać stan pliku. */
    private void waitForProducts(UUID storeId, ProductImportJobResponse job) {
        ProductImportJobResponse current = job;
        while (current.status() == ProductImportJobResponse.Status.RUNNING) {
            try {
                Thread.sleep(PRODUCT_JOB_POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Przerwano czekanie na import kartoteki", e);
            }
            current = products.job(storeId, job.id());
        }
    }

    /**
     * Błąd importu z opisem błędnych wierszy (numer wiersza i powód, najwyżej kilka), bo przy wgraniu przez agenta
     * nikt nie widzi odpowiedzi serwera, a sam napis „Plik zawiera błędne wiersze” nie mówi, co poprawić.
     */
    static String withRowErrors(ApiException e) {
        Object rows = e.getProperties().get("rowErrors");
        if (!(rows instanceof java.util.Collection<?> list) || list.isEmpty()) {
            return e.getMessage();
        }
        String details = list.stream().limit(5).map(String::valueOf).collect(java.util.stream.Collectors.joining("; "));
        return e.getMessage() + " " + details;
    }

    private void finish(UUID id, String error, boolean retryable) {
        active.remove(id);
        uploadIds.remove(id);
        transaction.executeWithoutResult(status -> files.findById(id).ifPresent(attempt -> {
            if (error == null) {
                attempt.imported();
            } else {
                attempt.failed(error, retryable);
            }
        }));
    }

    /** Wiadomość na żywo {@code imports}: Import danych odświeża stan, a po wgraniu wszystkie dane sklepu. */
    private void publish(UUID storeId, UUID id, ImportReportKind kind, String fileName, String action, String error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", action);
        fields.put("id", id);
        fields.put("kind", kind.name());
        fields.put("fileName", fileName);
        if (error != null) {
            fields.put("error", error);
        }
        fields.put("at", Instant.now());
        live.publish(storeId, "imports", fields);
    }

    private static String owner(StoreAgentService.AgentContext agent) {
        return agent.storeId() + ":agent:" + agent.agentId();
    }

    // --- Dla przeglądarki ----------------------------------------------------------------------------------------

    /** Ostatnie próby wgrania raportów przez agenta (Import danych). */
    public List<AgentReportFileResponse> recent(UUID storeId, int limit) {
        return transaction.execute(status -> files.findLatest(storeId, Limit.of(limit)).stream()
                .map(this::withLiveStatus).toList());
    }

    /** Próba „w toku” sprzed restartu serwera albo porzucona przez agenta jest pokazywana jako przerwana. */
    private AgentReportFileResponse withLiveStatus(AgentReportFile file) {
        AgentReportFileResponse response = mapper.toResponse(file);
        boolean started = file.getStatus() == AgentReportFile.Status.UPLOADING
                || file.getStatus() == AgentReportFile.Status.IMPORTING;
        if (started && !inProgress(file)) {
            return new AgentReportFileResponse(response.id(), response.kind(), response.fileName(),
                    response.fileModified(), response.fileSize(), "INTERRUPTED",
                    "Przerwane (restart serwera albo zerwane połączenie agenta), plik pójdzie ponownie",
                    response.startedAt(), response.finishedAt(), response.removedFromDiskAt(), false);
        }
        return response;
    }
}
