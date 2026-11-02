package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.ProtocolArticle;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.sales.dto.ProtocolReportGrouping;
import com.intermanage.backend.sales.dto.ProtocolReportResponse;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Article;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Figures;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Group;
import com.intermanage.backend.sales.dto.ProtocolReportsOverview;
import com.intermanage.backend.store.entity.ProtocolKind;
import com.intermanage.backend.store.entity.StoreProtoSettings;
import com.intermanage.backend.store.service.StoreProtoSettingsService;

/**
 * Raport protokołów: linie dokumentów jednego z sześciu protokołów (likwidacje, potrzeby sklepu, reklama, darowizna,
 * reprezentacja, MM) za okres, opcjonalnie z okresem porównywanym, zgrupowane po artykule, marce, ostatnim dostawcy
 * artykułu albo nomenklaturze. Każdy protokół liczy się tak samo, różni się tylko typem dokumentu. Protokoły ukryte
 * w Ustawieniach sklepu nie są dostępne.
 */
@Service
public class ProtocolReportService {

    /**
     * Typ dokumentu z raportu Wejścia/Wyjścia, którym system sklepu zapisuje protokół. Reklamy nie było jeszcze w
     * żadnym raporcie, więc nie znamy jej kodu.
     */
    private static final Map<ProtocolKind, DocumentType> DOCUMENT_TYPES = new EnumMap<>(Map.of(
            ProtocolKind.LIQUIDATION, DocumentType.PL,
            ProtocolKind.STORE_NEEDS, DocumentType.RWPO,
            ProtocolKind.DONATION, DocumentType.RWDA,
            ProtocolKind.REPRESENTATION, DocumentType.RWREP,
            ProtocolKind.TRANSFER, DocumentType.MM));

    private static final Figures ZERO = new Figures(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private final DocumentLineRepository lines;
    private final DocumentImportRepository documentImports;
    private final NomenclatureRepository nomenclatures;
    private final StoreProtoSettingsService protoSettings;

    public ProtocolReportService(DocumentLineRepository lines, DocumentImportRepository documentImports,
            NomenclatureRepository nomenclatures, StoreProtoSettingsService protoSettings) {
        this.lines = lines;
        this.documentImports = documentImports;
        this.nomenclatures = nomenclatures;
        this.protoSettings = protoSettings;
    }

    @Transactional(readOnly = true)
    public ProtocolReportsOverview overview(UUID storeId) {
        StoreProtoSettings settings = protoSettings.find(storeId);
        List<ProtocolReportsOverview.Protocol> protocols = Arrays.stream(ProtocolKind.values())
                .filter(kind -> !settings.isHidden(kind))
                .map(kind -> new ProtocolReportsOverview.Protocol(kind, kind.number(), kind.label(),
                        DOCUMENT_TYPES.containsKey(kind) ? DOCUMENT_TYPES.get(kind).name() : null))
                .toList();
        return new ProtocolReportsOverview(protocols, documentImports.findLastPeriodTo(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null));
    }

    /** Bez {@code previousFrom}/{@code previousTo} raport za sam okres, bez porównania. */
    @Transactional(readOnly = true)
    public ProtocolReportResponse report(UUID storeId, ProtocolKind protocol, ProtocolReportGrouping grouping,
            NomenclatureLevel level, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {
        if (protoSettings.find(storeId).isHidden(protocol)) {
            throw ApiException.badRequest("Protokół „" + protocol.label() + "” jest wyłączony w Ustawieniach sklepu");
        }
        checkPeriod(from, to);
        boolean compare = previousFrom != null || previousTo != null;
        if (compare) {
            if (previousFrom == null || previousTo == null) {
                throw ApiException.badRequest("Podaj początek i koniec okresu porównywanego");
            }
            checkPeriod(previousFrom, previousTo);
        }
        NomenclatureLevel nomenclatureLevel = level == null ? NomenclatureLevel.REGION : level;

        DocumentType type = DOCUMENT_TYPES.get(protocol);
        List<ProtocolArticle> articles = type == null ? List.of()
                : lines.sumProtocolArticles(storeId, type.name(), from, to, compare,
                        compare ? previousFrom : from, compare ? previousTo : to);

        Function<ProtocolArticle, GroupName> groupOf = switch (grouping) {
            case ARTICLE -> article -> new GroupName(article.getItm8(), article.getItm8(), article.getName());
            case BRAND -> article -> article.getBrand() == null ? new GroupName("", null, "Bez marki")
                    : new GroupName(article.getBrand(), null, article.getBrand());
            case SUPPLIER -> suppliers(storeId, articles);
            case NOMENCLATURE -> nomenclature(storeId, nomenclatureLevel);
        };

        Map<GroupName, List<ProtocolArticle>> grouped = articles.stream()
                .collect(Collectors.groupingBy(groupOf, LinkedHashMap::new, Collectors.toList()));
        Comparator<Group> byValue = Comparator
                .comparing((Group group) -> group.current().purchaseValue()).reversed()
                .thenComparing(group -> compare ? group.previous().purchaseValue() : BigDecimal.ZERO,
                        Comparator.reverseOrder())
                .thenComparing(Group::name, Comparator.nullsLast(Comparator.naturalOrder()));
        List<Group> groups = grouped.entrySet().stream()
                .map(entry -> group(entry.getKey(), entry.getValue(), compare))
                .sorted(byValue)
                .toList();

        return new ProtocolReportResponse(protocol, grouping,
                grouping == ProtocolReportGrouping.NOMENCLATURE ? nomenclatureLevel : null, from, to,
                compare ? previousFrom : null, compare ? previousTo : null,
                documentImports.findLastPeriodTo(storeId).orElse(null),
                sum(articles, ProtocolReportService::current),
                compare ? sum(articles, ProtocolReportService::previous) : null, groups);
    }

    private static void checkPeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("Początek okresu jest późniejszy niż koniec");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > SalesReportService.MAX_PERIOD_DAYS) {
            throw ApiException.badRequest("Okres może mieć najwyżej " + SalesReportService.MAX_PERIOD_DAYS + " dni");
        }
    }

    /** Ostatni dostawca z przyjęć PZ; artykuł, którego nikt nie przywiózł, bierze dostawcę z linii protokołu. */
    private Function<ProtocolArticle, GroupName> suppliers(UUID storeId, List<ProtocolArticle> articles) {
        Map<String, DocumentLineRepository.LastSupplier> last = articles.isEmpty() ? Map.of()
                : lines.findLastSuppliers(storeId, articles.stream().map(ProtocolArticle::getItm8).toList()).stream()
                        .collect(Collectors.toMap(DocumentLineRepository.LastSupplier::getItm8, Function.identity()));
        return article -> {
            DocumentLineRepository.LastSupplier supplier = last.get(article.getItm8());
            String code = supplier != null ? supplier.getSupplierCode() : article.getSupplierCode();
            String name = supplier != null ? supplier.getSupplierName() : article.getSupplierName();
            if (code == null || code.isBlank()) {
                return new GroupName("", null, "Bez dostawcy");
            }
            return new GroupName(code, code, name == null ? code : name.trim().replaceAll("\\s+", " "));
        };
    }

    /** Pozycja nomenklatury linii na wybranym poziomie; nazwa z nomenklatury sklepu. */
    private Function<ProtocolArticle, GroupName> nomenclature(UUID storeId, NomenclatureLevel level) {
        Map<String, String> names = nomenclatures.findByStoreIdAndLevelOrderByCode(storeId, level).stream()
                .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
        int digits = level.codeLength();
        return article -> {
            String code = article.getNomenclatureCode();
            if (code == null || code.length() < digits) {
                return new GroupName("", null, "Bez nomenklatury");
            }
            String prefix = code.substring(0, digits);
            return new GroupName(prefix, prefix, names.getOrDefault(prefix, "Nomenklatura " + prefix));
        };
    }

    private static Group group(GroupName name, List<ProtocolArticle> articles, boolean compare) {
        List<Article> rows = articles.stream()
                        .map(article -> new Article(article.getItm8(), article.getName(), article.getEan(),
                                article.getBrand(), current(article), compare ? previous(article) : null))
                        .sorted(Comparator.comparing((Article article) -> article.current().purchaseValue())
                                .reversed()
                                .thenComparing(article -> compare ? article.previous().purchaseValue()
                                        : BigDecimal.ZERO, Comparator.reverseOrder())
                                .thenComparing(Article::name, Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
        return new Group(name.key(), name.code(), name.name(), sum(articles, ProtocolReportService::current),
                compare ? sum(articles, ProtocolReportService::previous) : null, rows);
    }

    private static Figures current(ProtocolArticle article) {
        return new Figures(article.getCurrentQuantity(), article.getCurrentPurchaseValue(),
                article.getCurrentSaleValueGross());
    }

    private static Figures previous(ProtocolArticle article) {
        return new Figures(article.getPreviousQuantity(), article.getPreviousPurchaseValue(),
                article.getPreviousSaleValueGross());
    }

    private static Figures sum(List<ProtocolArticle> articles, Function<ProtocolArticle, Figures> figures) {
        return articles.stream().map(figures).reduce(ZERO, (a, b) -> new Figures(a.quantity().add(b.quantity()),
                a.purchaseValue().add(b.purchaseValue()), a.saleValueGross().add(b.saleValueGross())));
    }

    /** Grupa raportu: {@code key} łączy artykuły, {@code code} i {@code name} się pokazuje. */
    private record GroupName(String key, String code, String name) {

        @Override
        public boolean equals(Object other) {
            return other instanceof GroupName group && key.equals(group.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }
}
