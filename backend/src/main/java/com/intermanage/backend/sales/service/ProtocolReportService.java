package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.ProtocolArticle;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.sales.component.ProtocolReportPdf;
import com.intermanage.backend.sales.dto.ProtocolReportGrouping;
import com.intermanage.backend.sales.dto.ProtocolReportPrintRequest;
import com.intermanage.backend.sales.dto.ProtocolReportResponse;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Article;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Figures;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Group;
import com.intermanage.backend.sales.dto.ProtocolReportsOverview;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
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
    private final SaleRepository sales;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProtocolReportPdf reportPdf;

    public ProtocolReportService(DocumentLineRepository lines, DocumentImportRepository documentImports,
            NomenclatureRepository nomenclatures, StoreProtoSettingsService protoSettings, SaleRepository sales,
            StoreRepository stores, AppUserRepository users, ProtocolReportPdf reportPdf) {
        this.lines = lines;
        this.documentImports = documentImports;
        this.nomenclatures = nomenclatures;
        this.protoSettings = protoSettings;
        this.sales = sales;
        this.stores = stores;
        this.users = users;
        this.reportPdf = reportPdf;
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
        ReportPeriod.check(from, to);
        boolean compare = previousFrom != null || previousTo != null;
        if (compare) {
            if (previousFrom == null || previousTo == null) {
                throw ApiException.badRequest("Podaj początek i koniec okresu porównywanego");
            }
            ReportPeriod.check(previousFrom, previousTo);
        }
        NomenclatureLevel nomenclatureLevel = level == null ? NomenclatureLevel.REGION : level;

        sales.tuneForReport();
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

    /**
     * Wydruk raportu: tylko grupy o kluczach z {@code groups} (puste = wszystkie), w kolejności raportu, z sumami
     * wybranych. Np. likwidacje jednej marki albo dostawcy do pokazania dostawcy.
     */
    @Transactional(readOnly = true)
    public ExportFile pdf(UUID storeId, ProtocolKind protocol, ProtocolReportPrintRequest request, UUID userId) {
        ProtocolReportResponse report = report(storeId, protocol, request.grouping(), request.level(),
                request.from(), request.to(), request.previousFrom(), request.previousTo());
        boolean selection = request.groups() != null && !request.groups().isEmpty();
        Set<String> keys = selection ? new HashSet<>(request.groups()) : Set.of();
        Map<String, List<String>> partial = request.groupArticles() == null ? Map.of() : request.groupArticles();
        List<Group> groups = selection
                ? report.groups().stream().filter(group -> keys.contains(group.key()))
                        .map(group -> partial.containsKey(group.key()) ? onlyArticles(group, partial.get(group.key()))
                                : group)
                        .filter(group -> !group.articles().isEmpty())
                        .toList()
                : report.groups();
        if (selection && groups.isEmpty()) {
            throw ApiException.badRequest("Wybranych pozycji nie ma w raporcie za ten okres");
        }
        boolean compare = report.previousTotal() != null;
        Figures total = selection ? sumGroups(groups, Group::current) : report.total();
        Figures previousTotal = !compare ? null : selection ? sumGroups(groups, Group::previous) : report.previousTotal();

        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        String grouping = switch (report.grouping()) {
            case ARTICLE -> "Artykuły";
            case BRAND -> "Marka";
            case SUPPLIER -> "Ostatni dostawca";
            case NOMENCLATURE -> switch (report.level()) {
                case FAMILY -> "Rodzina";
                case SUBFAMILY -> "Podrodzina";
                default -> "Rejon";
            };
        };
        ProtocolReportPdf.Header header = new ProtocolReportPdf.Header(protocol.label(), store.getNumber(),
                store.getName(), grouping, "Porównanie", selection, printedBy);
        String name = selection && groups.size() == 1 ? "-" + fileNamePart(groups.getFirst().name()) : "";
        return new ExportFile("protokol-" + protocol.name().toLowerCase() + name + "-" + store.getNumber() + "-"
                + report.from() + "_" + report.to() + ".pdf", "application/pdf",
                reportPdf.pdf(header, report, groups, total, previousTotal, request.articles()));
    }

    /** Grupa z samymi wskazanymi artykułami i ich sumami. */
    private static Group onlyArticles(Group group, List<String> itm8s) {
        Set<String> wanted = itm8s == null ? Set.of() : new HashSet<>(itm8s);
        List<Article> articles = group.articles().stream().filter(article -> wanted.contains(article.itm8())).toList();
        boolean compare = group.previous() != null;
        return new Group(group.key(), group.code(), group.name(), sumFigures(articles.stream().map(Article::current)),
                compare ? sumFigures(articles.stream().map(Article::previous)) : null, articles);
    }

    private static Figures sumFigures(Stream<Figures> figures) {
        return figures.reduce(ZERO, (a, b) -> new Figures(a.quantity().add(b.quantity()),
                a.purchaseValue().add(b.purchaseValue()), a.saleValueGross().add(b.saleValueGross())));
    }

    private static String fileNamePart(String name) {
        String part = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ł', 'l').replace('Ł', 'L')
                .replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
        return part.length() > 40 ? part.substring(0, 40) : part;
    }

    private static Figures sumGroups(List<Group> groups, Function<Group, Figures> figures) {
        return groups.stream().map(figures).reduce(ZERO, (a, b) -> new Figures(a.quantity().add(b.quantity()),
                a.purchaseValue().add(b.purchaseValue()), a.saleValueGross().add(b.saleValueGross())));
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
