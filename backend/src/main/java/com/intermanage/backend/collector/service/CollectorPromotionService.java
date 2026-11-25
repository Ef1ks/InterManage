package com.intermanage.backend.collector.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;
import com.intermanage.backend.agent.repository.AgentMacroJobRepository;
import com.intermanage.backend.agent.service.AgentMacroService;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.collector.dto.CollectorPromotionDetails;
import com.intermanage.backend.collector.dto.CollectorPromotionResponse;
import com.intermanage.backend.collector.entity.CollectorPromotion;
import com.intermanage.backend.collector.entity.CollectorPromotionLine;
import com.intermanage.backend.collector.entity.StoreCollector;
import com.intermanage.backend.collector.mapper.CollectorPromotionMapper;
import com.intermanage.backend.collector.repository.CollectorPromotionLineRepository;
import com.intermanage.backend.collector.repository.CollectorPromotionRepository;
import com.intermanage.backend.collector.repository.StoreCollectorRepository;
import com.intermanage.backend.collector.service.StoreCollectorService.CollectorContext;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.Margin;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.LabelFile;
import com.intermanage.backend.promotion.component.PromoEntryMacro;
import com.intermanage.backend.promotion.dto.PromotionSetupArticle;
import com.intermanage.backend.promotion.service.PromotionSetupService;
import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.service.StoreFileQueueService;

/**
 * Promocje ustawione na kolektorze (3 Ustaw promocje): przyjęcie z podstawki, podgląd z ceną zwykłą i marżą z kartoteki,
 * wstawienie do Ustaw promocje (robi to przeglądarka, serwer tylko zapamiętuje) i ustawienie makrem na komputerze sklepu.
 * Po udanym makrze cenówki wszystkich artykułów (format PROMO, jak przy plikach cen promocji) idą do kolejki plików,
 * skąd agent zapisuje je w folderze protokołów.
 */
@Service
public class CollectorPromotionService {

    private static final Logger log = LoggerFactory.getLogger(CollectorPromotionService.class);
    static final int LIST_LIMIT = 50;
    private static final int MAX_LINES = 500;
    private static final int FIELDS = 6;
    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");
    private static final String LABEL_FORMAT = "PRO_PRO_FOR_67";
    private static final String LABEL_LOGIN = "kolektor";

    private final CollectorPromotionRepository promotions;
    private final CollectorPromotionLineRepository lines;
    private final StoreCollectorRepository collectors;
    private final AppUserRepository users;
    private final PromotionSetupService articles;
    private final AgentMacroService macros;
    private final AgentMacroJobRepository jobs;
    private final StoreFileQueueService fileQueue;
    private final CollectorPromotionMapper mapper;
    private final LiveEvents live;
    private final TransactionTemplate transaction;

    public CollectorPromotionService(CollectorPromotionRepository promotions, CollectorPromotionLineRepository lines,
            StoreCollectorRepository collectors, AppUserRepository users, PromotionSetupService articles,
            AgentMacroService macros, AgentMacroJobRepository jobs, StoreFileQueueService fileQueue,
            CollectorPromotionMapper mapper, LiveEvents live, TransactionTemplate transaction) {
        this.promotions = promotions;
        this.lines = lines;
        this.collectors = collectors;
        this.users = users;
        this.articles = articles;
        this.macros = macros;
        this.jobs = jobs;
        this.fileQueue = fileQueue;
        this.mapper = mapper;
        this.live = live;
        this.transaction = transaction;
    }

    /**
     * Promocje z kolektora: wiersz na artykuł, pola oddzielone tabulatorem (ITM8, EAN, nazwa, marka, cena promocji,
     * koniec RRRRMMDD). Ta sama wysyłka drugi raz (urwane potwierdzenie) zwraca zapisaną. {@code start}: początek
     * operacji RRRRMMDD albo pusty (dziś).
     */
    @Transactional
    public CollectorPromotion receive(CollectorContext context, UUID id, String started, String start, boolean macro,
            String text) {
        CollectorPromotion existing = promotions.findById(id).orElse(null);
        if (existing != null) {
            if (!existing.getCollector().getId().equals(context.collectorId())) {
                throw ApiException.conflict("Te promocje są już z innego kolektora");
            }
            return existing;
        }
        List<CollectorPromotionLine> parsed = parse(id, text);
        StoreCollector collector = collectors.findById(context.collectorId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kolektora"));
        LocalDate end = parsed.stream().map(CollectorPromotionLine::getDateTo).max(LocalDate::compareTo).orElseThrow();
        CollectorPromotion promotion = promotions.saveAndFlush(new CollectorPromotion(id, collector,
                startedAt(started), day(start, false), end, parsed.size(), macro));
        lines.saveAll(parsed);
        live.publish(context.storeId(), "collector-lists", Map.of("action", "promotion", "id", id.toString(),
                "collector", collector.getName(), "lines", parsed.size(), "macro", macro));
        return promotion;
    }

    /** Kolektor poprosił o makro, a ono jeszcze nie ruszyło i nie odmówiło (np. ponowiona wysyłka). */
    public static boolean macroPending(CollectorPromotion promotion) {
        return promotion.isMacroRequested() && promotion.getMacroJobId() == null && promotion.getMacroError() == null;
    }

    @Transactional(readOnly = true)
    public List<CollectorPromotionResponse> list(UUID storeId) {
        return responses(promotions.findLatest(storeId, Limit.of(LIST_LIMIT)));
    }

    @Transactional(readOnly = true)
    public long countWaiting(UUID storeId) {
        return promotions.countWaiting(storeId);
    }

    @Transactional(readOnly = true)
    public CollectorPromotionDetails details(UUID storeId, UUID id) {
        CollectorPromotion promotion = find(storeId, id);
        List<CollectorPromotionLine> found = lines.findByPromotionIdOrderByPosition(id);
        Map<String, PromotionSetupArticle> catalog = catalog(storeId, found);
        List<CollectorPromotionDetails.Row> rows = found.stream().map(line -> row(line, catalog.get(key(line.getItm8()))))
                .toList();
        return new CollectorPromotionDetails(responses(List.of(promotion)).getFirst(), rows);
    }

    /**
     * Makro na komputerze sklepu: operacja z nowym kodem, początek z kolektora (gdy po dziś), artykuły w kolejności
     * z kolektora z EAN-em i stawką VAT z kartoteki. {@code userId} pusty = zlecił kolektor.
     */
    @Transactional
    public CollectorPromotionResponse runMacro(UUID storeId, UUID id, UUID agentId, UUID userId) {
        CollectorPromotion promotion = find(storeId, id);
        if (promotion.getMacroJobId() != null) {
            AgentMacroJob job = jobs.findById(promotion.getMacroJobId()).orElse(null);
            if (job != null && job.getFinishedAt() == null) {
                throw ApiException.conflict("Te promocje czekają już na komputer sklepu albo właśnie się ustawiają");
            }
        }
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        LocalDate start = promotion.getStartDate() != null && promotion.getStartDate().isAfter(today)
                ? promotion.getStartDate() : null;
        LocalDate from = start != null ? start : today;
        List<CollectorPromotionLine> found = lines.findByPromotionIdOrderByPosition(id);
        Map<String, PromotionSetupArticle> catalog = catalog(storeId, found);
        List<PromoEntryMacro.Item> items = new ArrayList<>();
        int ended = 0;
        for (CollectorPromotionLine line : found) {
            if (line.getDateTo().isBefore(from)) {
                ended++;
                continue;
            }
            PromotionSetupArticle article = catalog.get(key(line.getItm8()));
            String ean = article != null && article.ean() != null ? article.ean() : line.getEan();
            items.add(new PromoEntryMacro.Item(ean, line.getPrice(), line.getDateTo(),
                    article == null ? null : PromoEntryMacro.vatCode(article.vatPercent())));
        }
        if (items.isEmpty()) {
            throw ApiException.conflict("Wszystkie promocje z tej listy kończą się przed " + from + ".");
        }
        String code = PromoEntryMacro.randomCode();
        String macro = PromoEntryMacro.build(code, PromoEntryMacro.name(from, PromoEntryMacro.end(items)), start, items);
        String title = "Ustaw promocje z kolektora (" + items.size() + " art.)"
                + (ended > 0 ? ", pominięte zakończone: " + ended : "") + " · " + promotion.getCollector().getName();
        AgentMacroApi.Job job = macros.promotions(storeId, title, macro, items.size(), agentId, userId);
        promotion.macroStarted(job.id());
        promotions.flush();
        live.publish(storeId, "collector-lists", Map.of("action", "promotion-macro", "id", id.toString()));
        return responses(List.of(promotion)).getFirst();
    }

    /** Makro zlecone przez kolektor nie ruszyło: opis zostaje przy promocjach, a kolektor dostaje go w odpowiedzi. */
    @Transactional
    public void macroFailed(UUID storeId, UUID id, String message) {
        find(storeId, id).macroFailed(message);
        promotions.flush();
    }

    /** Przeglądarka wstawiła promocje do Ustaw promocje. */
    @Transactional
    public CollectorPromotionResponse markInserted(UUID storeId, UUID id, UUID actorId) {
        CollectorPromotion promotion = find(storeId, id);
        promotion.markInserted(users.getReferenceById(actorId));
        promotions.flush();
        return responses(List.of(promotion)).getFirst();
    }

    @Transactional
    public List<CollectorPromotionResponse> remove(UUID storeId, UUID id, UUID actorId) {
        find(storeId, id).markDeleted(users.getReferenceById(actorId));
        promotions.flush();
        return list(storeId);
    }

    @Transactional
    public List<CollectorPromotionResponse> restore(UUID storeId, UUID id) {
        promotions.findById(id).filter(found -> found.getStoreId().equals(storeId) && found.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono promocji z kolektora"))
                .restore();
        promotions.flush();
        return list(storeId);
    }

    /** Koniec makra promocji: po udanym cenówki wszystkich artykułów do kolejki plików sklepu. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterMacro(AgentMacroService.MacroEnded event) {
        if (event.kind() != AgentMacroKind.PROMO_ENTRY || event.status() != AgentMacroStatus.DONE) {
            if (event.kind() == AgentMacroKind.PROMO_ENTRY) {
                live.publish(event.storeId(), "collector-lists", Map.of("action", "promotion-macro"));
            }
            return;
        }
        Thread.ofVirtual().name("collector-promo-labels-" + event.jobId()).start(() -> {
            try {
                transaction.executeWithoutResult(status -> queueLabels(event.jobId()));
                live.publish(event.storeId(), "collector-lists", Map.of("action", "promotion-macro"));
            } catch (RuntimeException e) {
                log.warn("Cenówki promocji z kolektora po makrze {}", event.jobId(), e);
            }
        });
    }

    private void queueLabels(UUID jobId) {
        CollectorPromotion promotion = promotions.findByMacroJobId(jobId).orElse(null);
        if (promotion == null || promotion.getLabelsFileId() != null) {
            return;
        }
        Set<String> eans = new LinkedHashSet<>();
        List<CollectorPromotionLine> found = lines.findByPromotionIdOrderByPosition(promotion.getId());
        Map<String, PromotionSetupArticle> catalog = catalog(promotion.getStoreId(), found);
        for (CollectorPromotionLine line : found) {
            PromotionSetupArticle article = catalog.get(key(line.getItm8()));
            eans.add(article != null && article.ean() != null ? article.ean() : line.getEan());
        }
        byte[] content = LabelFile.content(eans.stream().map(ean -> new LabelFile.Line(ean, 1)).toList());
        if (content.length == 0) {
            return;
        }
        UUID fileId = fileQueue.add(promotion.getStoreId(), DefaultFolders.PROTO, "Protokół", null, null,
                LabelFile.fileName(LABEL_LOGIN, LABEL_FORMAT), content, null).id();
        promotion.labelsQueued(fileId);
        promotions.flush();
    }

    private Map<String, PromotionSetupArticle> catalog(UUID storeId, List<CollectorPromotionLine> found) {
        // Po EAN-ie i ITM8: krótki ITM8 bywa też czyimś EAN-8, a wynik i tak łączy się po ITM8 artykułu.
        List<String> codes = found.stream().flatMap(line -> java.util.stream.Stream.of(key(line.getEan()),
                key(line.getItm8()))).filter(code -> !code.isEmpty()).distinct().toList();
        return articles.articles(storeId, codes).stream().filter(article -> article.itm8() != null)
                .collect(Collectors.toMap(article -> key(article.itm8()), Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
    }

    private static CollectorPromotionDetails.Row row(CollectorPromotionLine line, PromotionSetupArticle article) {
        BigDecimal regular = article == null ? null : article.regularPrice();
        BigDecimal purchase = article == null ? null : article.lastPurchasePrice();
        BigDecimal vat = article == null ? null : article.vatPercent();
        BigDecimal price = line.getPrice();
        BigDecimal discount = regular == null || regular.signum() <= 0 ? null
                : BigDecimal.ONE.subtract(price.divide(regular, 6, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP);
        BigDecimal margin = purchase == null || vat == null || price.signum() <= 0 ? null
                : Margin.percent(price, vat, purchase, 2);
        return new CollectorPromotionDetails.Row(line.getItm8(), article != null && article.ean() != null
                ? article.ean() : line.getEan(), article != null && article.name() != null ? article.name()
                : line.getName(), article != null && article.brand() != null ? article.brand() : line.getBrand(),
                regular, price, discount, purchase, margin, line.getDateTo(), article != null);
    }

    private CollectorPromotion find(UUID storeId, UUID id) {
        return promotions.findActive(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono promocji z kolektora"));
    }

    private List<CollectorPromotionResponse> responses(List<CollectorPromotion> found) {
        List<UUID> jobIds = found.stream().map(CollectorPromotion::getMacroJobId).filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, AgentMacroJob> byId = jobIds.isEmpty() ? Map.of() : jobs.findAllById(jobIds).stream()
                .collect(Collectors.toMap(AgentMacroJob::getId, Function.identity()));
        return found.stream().map(promotion -> {
            AgentMacroJob job = promotion.getMacroJobId() == null ? null : byId.get(promotion.getMacroJobId());
            CollectorPromotionResponse.Macro macro = job == null ? null : new CollectorPromotionResponse.Macro(
                    job.getId(), job.getStatus().name(), job.getResultMessage() != null ? job.getResultMessage()
                            : job.getWaitReason(), job.getFinishedAt());
            return mapper.toResponse(promotion, macro);
        }).toList();
    }

    private static List<CollectorPromotionLine> parse(UUID id, String text) {
        List<CollectorPromotionLine> result = new ArrayList<>();
        for (String row : (text == null ? "" : text).split("\n")) {
            if (row.isBlank()) {
                continue;
            }
            String[] fields = row.replace("\r", "").split("\t", -1);
            if (fields.length < FIELDS || result.size() >= MAX_LINES) {
                throw ApiException.badRequest("Promocje z kolektora są uszkodzone");
            }
            if (!fields[0].matches("\\d{1,13}") || !fields[1].matches("\\d{1,14}")) {
                throw ApiException.badRequest("Zły kod w promocjach z kolektora: " + cut(fields[1], 20));
            }
            if (!fields[4].matches("\\d{1,5}(\\.\\d{1,2})?")) {
                throw ApiException.badRequest("Zła cena w promocjach z kolektora: " + cut(fields[4], 20));
            }
            BigDecimal price = new BigDecimal(fields[4]);
            if (price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
                throw ApiException.badRequest("Zła cena w promocjach z kolektora: " + fields[4]);
            }
            result.add(new CollectorPromotionLine(id, result.size() + 1, fields[0], fields[1], cut(fields[2], 100),
                    cut(fields[3], 100), price, day(fields[5], true)));
        }
        if (result.isEmpty()) {
            throw ApiException.badRequest("Promocje z kolektora są puste");
        }
        return result;
    }

    private static LocalDate day(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw ApiException.badRequest("Promocja z kolektora bez końca");
            }
            return null;
        }
        try {
            return LocalDate.parse(value.strip(), DAY);
        } catch (DateTimeParseException exception) {
            throw ApiException.badRequest("Zła data w promocjach z kolektora: " + cut(value, 20));
        }
    }

    private static LocalDateTime startedAt(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDateTime.parse(value, STARTED);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String key(String code) {
        return code == null ? "" : code.replaceFirst("^0+", "");
    }

    private static String cut(String value, int max) {
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
