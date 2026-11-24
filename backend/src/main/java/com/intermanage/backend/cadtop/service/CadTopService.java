package com.intermanage.backend.cadtop.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.cadtop.component.CadPlanParser;
import com.intermanage.backend.cadtop.component.CadSchedule;
import com.intermanage.backend.cadtop.component.CadTopParser;
import com.intermanage.backend.cadtop.dto.CadTopOverview;
import com.intermanage.backend.cadtop.entity.CadPlan;
import com.intermanage.backend.cadtop.entity.CadPlanCheck;
import com.intermanage.backend.cadtop.entity.CadPlanEntry;
import com.intermanage.backend.cadtop.entity.CadStoreRegion;
import com.intermanage.backend.cadtop.entity.CadTopFile;
import com.intermanage.backend.cadtop.repository.CadPlanCheckRepository;
import com.intermanage.backend.cadtop.repository.CadPlanRepository;
import com.intermanage.backend.cadtop.repository.CadStoreRegionRepository;
import com.intermanage.backend.cadtop.repository.CadTopFileRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.service.MeaService;

import tools.jackson.databind.JsonNode;

/**
 * Pliki TOP cadencier i plan wejść numerów cadencier z portalu Grupy Muszkieterów. TOP-y są na liście {@code /api/cad/}
 * (sekcja {@code top}, kilka numerów naraz), plan ma stałą nazwę spoza listy. Który TOP obowiązuje, mówi plan, a nie
 * data pliku: portal wystawia plik kilka dni przed wejściem cadencier.
 */
@Service
public class CadTopService {

    private static final Logger log = LoggerFactory.getLogger(CadTopService.class);
    private static final long MAX_PLAN_BYTES = 5L * 1024 * 1024;
    /** Plan roku w portalu. Aktualizacja bywa pod inną nazwą: wtedy wgrywa się ją ręcznie. */
    private static final String PLAN_PATH = "htmldownload/cad_planing_%d_pdv.xlsx";
    static final Duration PLAN_CHECK_INTERVAL = Duration.ofHours(20);
    /** Bez planu na dziś pytamy częściej. */
    static final Duration PLAN_RETRY_INTERVAL = Duration.ofHours(3);
    /** Tyle dni przed wejściem numeru ostrzegamy, że portal nie ma jeszcze jego pliku. */
    static final int MISSING_FILE_DAYS = 3;
    /** Tyle dni przed ostatnim wejściem w planach prosimy o plan na kolejny rok. */
    static final int PLAN_END_DAYS = 30;
    private static final int PAST_ROWS = 3;
    private static final int UPCOMING_ROWS = 6;
    private static final Pattern REGION_IN_PATH = Pattern.compile("\\d{6}_(\\p{L}+)\\.xlsx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGION_IN_NAME = Pattern.compile("^cad[\\d_]+_(\\p{L}+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.MM.yyyy");

    private final CadPlanRepository plans;
    private final CadPlanCheckRepository checks;
    private final CadStoreRegionRepository regions;
    private final CadTopFileRepository files;
    private final CadPlanParser planParser;
    private final CadTopParser topParser;
    private final AppUserRepository users;
    private final MeaService mea;
    private final MurphysoftClient client;
    private final TransactionTemplate transaction;
    /** Pliki portalu (ścieżka|data zmiany albo skrót planu), których nie dało się odczytać; do restartu pomijane. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public CadTopService(CadPlanRepository plans, CadPlanCheckRepository checks, CadStoreRegionRepository regions,
            CadTopFileRepository files, CadPlanParser planParser, CadTopParser topParser, AppUserRepository users,
            MeaService mea, MurphysoftClient client, TransactionTemplate transaction) {
        this.plans = plans;
        this.checks = checks;
        this.regions = regions;
        this.files = files;
        this.planParser = planParser;
        this.topParser = topParser;
        this.users = users;
        this.mea = mea;
        this.client = client;
        this.transaction = transaction;
    }

    // --- Odczyt ---

    @Transactional(readOnly = true)
    public CadTopOverview overview(UUID storeId) {
        boolean configured = mea.configuredStores().contains(storeId);
        String region = regions.findById(storeId).map(CadStoreRegion::getRegion).orElse(null);
        List<CadPlan> active = plans.findActive();
        CadSchedule schedule = CadPriceService.schedule(active);
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        Map<String, CadTopFile> byNumber = region == null ? Map.of()
                : files.findByRegionAndReplacedAtIsNull(region).stream()
                        .collect(Collectors.toMap(CadTopFile::getNrCad, Function.identity()));
        return new CadTopOverview(configured, region, rows(schedule, byNumber, today),
                active.stream().map(CadTopService::plan).toList(),
                warnings(configured, region, schedule, byNumber, active, today));
    }

    private List<CadTopOverview.Row> rows(CadSchedule schedule, Map<String, CadTopFile> byNumber, LocalDate today) {
        Map<UUID, CadTopFileRepository.Counts> counts = byNumber.isEmpty() ? Map.of()
                : files.countMarks(byNumber.values().stream().map(CadTopFile::getId).toList()).stream()
                        .collect(Collectors.toMap(CadTopFileRepository.Counts::getFileId, Function.identity()));
        List<CadSchedule.Entry> entries = schedule.entries();
        int current = schedule.currentOn(today).map(entries::indexOf).orElse(-1);
        List<CadTopOverview.Row> rows = new ArrayList<>();
        byNumber.values().stream().filter(file -> schedule.find(file.getNrCad()).isEmpty())
                .sorted(Comparator.comparing(CadTopFile::getSourceUpdatedAt).reversed())
                .forEach(file -> rows.add(new CadTopOverview.Row(file.getNrCad(), null, null,
                        CadTopOverview.Status.NOT_PLANNED, file(file, counts))));
        for (int i = Math.max(0, current - PAST_ROWS); i < Math.min(entries.size(), current + 1 + UPCOMING_ROWS); i++) {
            CadSchedule.Entry entry = entries.get(i);
            CadTopOverview.Status status = i < current ? CadTopOverview.Status.PAST
                    : i == current ? CadTopOverview.Status.CURRENT : CadTopOverview.Status.UPCOMING;
            CadTopFile file = byNumber.get(entry.nrCad());
            rows.add(new CadTopOverview.Row(entry.nrCad(), entry.startsOn(), entry.endsOn(), status,
                    file == null ? null : file(file, counts)));
        }
        return rows;
    }

    private static CadTopOverview.File file(CadTopFile file, Map<UUID, CadTopFileRepository.Counts> counts) {
        CadTopFileRepository.Counts marks = counts.get(file.getId());
        return new CadTopOverview.File(file.getFileName(), file.getSourceUpdatedAt(), file.getDownloadedAt(),
                file.getArticleCount(), marks == null ? 0 : marks.getBtop(), marks == null ? 0 : marks.getB(),
                marks == null ? 0 : marks.getA(), marks == null ? 0 : marks.getNews());
    }

    private static CadTopOverview.Plan plan(CadPlan plan) {
        return new CadTopOverview.Plan(plan.getYear(), plan.getFileName(), plan.getEntryCount(), plan.getModifiedAt(),
                plan.getUploadedAt(), plan.getUploadedBy() == null ? null : plan.getUploadedBy().getDisplayName(),
                plan.isFromPortal());
    }

    private List<String> warnings(boolean configured, String region, CadSchedule schedule,
            Map<String, CadTopFile> byNumber, List<CadPlan> active, LocalDate today) {
        List<String> warnings = new ArrayList<>();
        if (!configured) {
            warnings.add("Sklep nie ma klucza API portalu (Moduł MEA → Klucze API), więc nie pobieramy jego plików TOP.");
        } else if (region == null) {
            warnings.add("Portal nie pokazał jeszcze plików TOP tego sklepu. Pobiorą się przy najbliższym sprawdzeniu.");
        }
        schedule.currentOn(today).ifPresentOrElse(entry -> {
            if (region != null && !byNumber.containsKey(entry.nrCad())) {
                warnings.add("Cadencier " + entry.nrCad() + " obowiązuje od " + DAY.format(entry.startsOn())
                        + ", a portal nie ma jego pliku TOP.");
            }
        }, () -> warnings.add("Brak planu cadencier na dziś. Wgraj plan z intranetu."));
        schedule.nextAfter(today).ifPresent(entry -> {
            if (region != null && !byNumber.containsKey(entry.nrCad())
                    && !entry.startsOn().isAfter(today.plusDays(MISSING_FILE_DAYS))) {
                warnings.add("Cadencier " + entry.nrCad() + " wchodzi " + DAY.format(entry.startsOn())
                        + ", a portal nie ma jeszcze jego pliku TOP.");
            }
        });
        List<String> notPlanned = byNumber.keySet().stream().filter(number -> schedule.find(number).isEmpty()).sorted()
                .toList();
        if (!notPlanned.isEmpty() && !schedule.entries().isEmpty()) {
            warnings.add("Portal ma plik TOP spoza planu (" + String.join(", ", notPlanned)
                    + "): plan jest pewnie nieaktualny, wgraj nowszy.");
        }
        if (!schedule.entries().isEmpty()) {
            CadSchedule.Entry last = schedule.entries().getLast();
            if (last.startsOn().isBefore(today.plusDays(PLAN_END_DAYS))) {
                warnings.add("Plan kończy się na cadencier " + last.nrCad() + " (od " + DAY.format(last.startsOn())
                        + "). Wgraj plan na kolejny rok.");
            }
        }
        for (CadPlan plan : active) {
            // Plan wgrany ręcznie to zwykle aktualizacja pod inną nazwą, więc brak pliku w portalu jest wtedy oczekiwany.
            if (plan.isFromPortal() && plan.getYear() >= today.getYear()) {
                checks.findById((short) plan.getYear()).filter(check -> check.getError() != null)
                        .ifPresent(check -> warnings.add("Nie udało się sprawdzić planu " + plan.getYear()
                                + " w portalu (" + check.getError() + "). Jeśli wyszła aktualizacja pod inną nazwą, "
                                + "wgraj ją ręcznie."));
            }
        }
        return warnings;
    }

    // --- Pobieranie z portalu ---

    /** Nowe pliki TOP z listy portalu sklepu (od najstarszego) i jego rejon. Zwraca liczbę pobranych. */
    public int syncTops(UUID storeId) {
        MeaService.Session session = mea.session(storeId);
        JsonNode list = client.cadList(session.shopId(), session.token());
        List<PortalFile> found = new ArrayList<>();
        for (JsonNode node : list.path("top")) {
            String path = node.path("path").asString("");
            String name = node.path("name").asString("");
            if (path.isBlank()) {
                continue;
            }
            String region = region(path, name);
            if (region == null) {
                log.warn("Plik TOP {} ({}) bez rejonu w nazwie: pominięty", name, path);
                continue;
            }
            found.add(new PortalFile(region, name.isBlank() ? path : name, path,
                    node.path("update_date").asString("")));
        }
        Set<String> shown = found.stream().map(PortalFile::region).collect(Collectors.toSet());
        if (shown.size() == 1) {
            saveRegion(storeId, shown.iterator().next());
        } else if (shown.size() > 1) {
            log.warn("Portal pokazuje sklepowi {} pliki TOP kilku rejonów: {}", storeId, shown);
        }
        found.sort(Comparator.comparing(PortalFile::updatedAt));
        int downloaded = 0;
        MurphysoftClient.Unavailable unavailable = null;
        for (PortalFile file : found) {
            String key = file.path() + "|" + file.updatedAt();
            if (failed.contains(key) || known(file)) {
                continue;
            }
            byte[] bytes;
            CadTopParser.Parsed parsed;
            try {
                bytes = client.cadFile(session.shopId(), session.token(), file.path());
                parsed = withTempFile(bytes, topParser::parse);
            } catch (MurphysoftClient.Unavailable exception) {
                // Jeden niedostępny plik nie zatrzymuje nowszych.
                log.warn("Plik TOP {} niedostępny: {}", file.name(), exception.getMessage());
                unavailable = exception;
                continue;
            } catch (InvalidReportException | InvalidWorkbookException | IOException exception) {
                log.warn("Plik TOP {} z portalu nieczytelny: {} {}", file.name(), exception.getMessage(),
                        exception instanceof InvalidReportException report ? report.errors() : "");
                failed.add(key);
                continue;
            }
            if (Boolean.TRUE.equals(transaction.execute(status -> saveTop(file, parsed, sha256(bytes))))) {
                downloaded++;
            }
        }
        if (downloaded == 0 && unavailable != null) {
            throw unavailable;
        }
        return downloaded;
    }

    /**
     * Sprawdza plany bieżącego i następnego roku; z {@code force} także te sprawdzone niedawno. Planu bieżącego roku nie
     * ma po co pobierać, gdy dzisiejszy numer daje już plan następnego (2027 zaczyna się w październiku 2026).
     */
    public int syncPlans(UUID storeId, boolean force) {
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        int year = today.getYear();
        int downloaded = 0;
        for (int checked : new int[] {year + 1, year}) {
            Integer coveringYear = transaction.execute(status -> CadPriceService.schedule(plans.findActive()).currentOn(today)
                    .map(CadSchedule.Entry::planYear).orElse(null));
            Duration interval = coveringYear == null ? PLAN_RETRY_INTERVAL : PLAN_CHECK_INTERVAL;
            if (checked == year && coveringYear != null && coveringYear > year) {
                continue;
            }
            CadPlanCheck last = checks.findById((short) checked).orElse(null);
            if (!force && last != null && last.getCheckedAt().isAfter(Instant.now().minus(interval))) {
                continue;
            }
            MeaService.Session session = mea.session(storeId);
            String path = PLAN_PATH.formatted(checked);
            String error = null;
            try {
                byte[] bytes = client.cadFile(session.shopId(), session.token(), path);
                if (savePortalPlan(path, bytes)) {
                    downloaded++;
                }
            } catch (MurphysoftClient.Unavailable | InvalidReportException | InvalidWorkbookException
                    | IOException exception) {
                error = exception.getMessage();
            }
            recordCheck(checked, error);
        }
        return downloaded;
    }

    private record PortalFile(String region, String name, String path, String updatedAt) {
    }

    /** Rejon z nazwy pliku ({@code 261106_swadzim.xlsx}), a bez niej z opisu („cad45_1_Swadzim - …”). */
    static String region(String path, String name) {
        Matcher matcher = REGION_IN_PATH.matcher(path);
        if (!matcher.find()) {
            matcher = REGION_IN_NAME.matcher(name);
            if (!matcher.find()) {
                return null;
            }
        }
        String region = Normalizer.normalize(matcher.group(1), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'L').toLowerCase(Locale.ROOT);
        return region.length() <= 40 ? region : region.substring(0, 40);
    }

    private boolean known(PortalFile file) {
        return files.existsByRegionAndSourcePathAndSourceUpdatedAt(file.region(), file.path(), file.updatedAt());
    }

    private void saveRegion(UUID storeId, String region) {
        transaction.executeWithoutResult(status -> {
            CadStoreRegion saved = regions.findById(storeId).orElseGet(() -> new CadStoreRegion(storeId));
            if (!region.equals(saved.getRegion())) {
                saved.change(region);
                regions.save(saved);
            }
        });
    }

    private boolean saveTop(PortalFile portal, CadTopParser.Parsed parsed, String sha256) {
        plans.lockForImport();
        if (known(portal)) {
            return false;
        }
        CadTopFile saved = new CadTopFile(portal.region(), parsed.nrCad(), portal.name(), portal.path(),
                portal.updatedAt(), sha256, parsed.articles().size());
        files.findByRegionAndNrCadAndReplacedAtIsNull(portal.region(), parsed.nrCad()).ifPresent(previous -> {
            if (previous.getSourceUpdatedAt().compareTo(portal.updatedAt()) > 0) {
                // Starszy plik tego samego numeru zostaje tylko jako historia.
                saved.replace();
            } else {
                previous.replace();
                // Aktywny może być jeden plik numeru w rejonie (indeks unikalny).
                files.flush();
            }
        });
        files.saveAndFlush(saved);
        insertArticles(saved.getId(), parsed.articles());
        log.info("TOP cadencier {} ({}): {} artykułów z pliku {}", parsed.nrCad(), portal.region(),
                parsed.articles().size(), portal.name());
        return true;
    }

    private void insertArticles(UUID fileId, List<CadTopParser.Article> articles) {
        files.insertArticles(fileId,
                articles.stream().map(CadTopParser.Article::itm8).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::ean).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::name).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::brand).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::supplierCode).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::productType).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::purchasePrice).toArray(BigDecimal[]::new),
                articles.stream().map(CadTopParser.Article::salePrice).toArray(BigDecimal[]::new),
                articles.stream().map(CadTopParser.Article::pack).toArray(Integer[]::new),
                articles.stream().map(CadTopParser.Article::vatPercent).toArray(BigDecimal[]::new),
                articles.stream().map(CadTopParser.Article::topMark).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::subfamilyCode).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::category).toArray(String[]::new),
                articles.stream().map(CadTopParser.Article::isNew).toArray(Boolean[]::new),
                articles.stream().map(CadTopParser.Article::marginPercent).toArray(BigDecimal[]::new),
                articles.stream().map(CadTopParser.Article::networkRotation).toArray(BigDecimal[]::new));
    }

    private boolean savePortalPlan(String path, byte[] bytes) throws IOException, InvalidWorkbookException,
            InvalidReportException {
        String sha256 = sha256(bytes);
        // Plik, który już był (także zastąpiony wgraną aktualizacją), niczego nie zmienia.
        if (plans.existsBySha256(sha256)) {
            return false;
        }
        CadPlanParser.Parsed parsed = withTempFile(bytes, planParser::parse);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            plans.lockForImport();
            CadPlan plan = new CadPlan(parsed.year(), fileName, sha256, parsed.modifiedAt(), path, null);
            CadPlan previous = plans.findByYearAndReplacedAtIsNull((short) parsed.year()).orElse(null);
            if (plans.existsBySha256(sha256) || (previous != null && plan.isOlderThan(previous))) {
                return false;
            }
            save(plan, previous, parsed, null);
            return true;
        }));
    }

    private void recordCheck(int year, String error) {
        transaction.executeWithoutResult(status -> {
            CadPlanCheck check = checks.findById((short) year).orElseGet(() -> new CadPlanCheck(year));
            check.record(error);
            checks.save(check);
        });
    }

    // --- Plan wgrany ręcznie ---

    public CadTopOverview uploadPlan(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() == null ? "plan.xlsx"
                : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do wgrania");
        }
        if (file.getSize() > MAX_PLAN_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 5 MB)");
        }
        byte[] bytes;
        CadPlanParser.Parsed parsed;
        try {
            bytes = file.getBytes();
            parsed = withTempFile(bytes, planParser::parse);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (InvalidWorkbookException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
        String sha256 = sha256(bytes);
        transaction.executeWithoutResult(status -> {
            plans.lockForImport();
            AppUser by = users.getReferenceById(userId);
            CadPlan plan = new CadPlan(parsed.year(), fileName, sha256, parsed.modifiedAt(), null, by);
            CadPlan previous = plans.findByYearAndReplacedAtIsNull((short) parsed.year()).orElse(null);
            if (previous != null && previous.getSha256().equals(sha256)) {
                throw ApiException.conflict("Ten plan na " + parsed.year() + " jest już wgrany");
            }
            if (previous != null && plan.isOlderThan(previous)) {
                throw ApiException.conflict("Ten plan na " + parsed.year() + " (zapisany "
                        + day(parsed.modifiedAt()) + ") jest starszy niż wgrany (" + day(previous.getModifiedAt())
                        + "). Wgraj najnowszy.");
            }
            save(plan, previous, parsed, by);
        });
        return transaction.execute(status -> overview(storeId));
    }

    private void save(CadPlan plan, CadPlan previous, CadPlanParser.Parsed parsed, AppUser by) {
        if (previous != null) {
            previous.replace(by);
            // Aktywny może być jeden plan roku (indeks unikalny).
            plans.flush();
        }
        parsed.entries().forEach(entry -> plan.addEntry(new CadPlanEntry(entry.lp(), entry.nrCad(),
                entry.startsOn(), entry.endsOn(), entry.comment())));
        plans.saveAndFlush(plan);
        log.info("Plan cadencier {} ({}): {} wejść z pliku {}", parsed.year(),
                plan.isFromPortal() ? "portal" : "wgrany", parsed.entries().size(), plan.getFileName());
    }

    private static String day(Instant instant) {
        return DAY.format(instant.atZone(StoreTime.ZONE));
    }

    // --- Pliki ---

    @FunctionalInterface
    private interface FileParser<T> {
        T parse(Path file) throws IOException, InvalidWorkbookException, InvalidReportException;
    }

    private static <T> T withTempFile(byte[] bytes, FileParser<T> parser) throws IOException,
            InvalidWorkbookException, InvalidReportException {
        Path temp = Files.createTempFile("cad-", ".xlsx");
        try {
            Files.write(temp, bytes);
            return parser.parse(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Brak algorytmu SHA-256", exception);
        }
    }
}
