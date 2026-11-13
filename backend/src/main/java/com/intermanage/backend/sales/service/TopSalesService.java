package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.component.Percent;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.promotion.service.PromotionReportService;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.dto.TopSalesLevel;
import com.intermanage.backend.sales.dto.TopSalesMetric;
import com.intermanage.backend.sales.dto.TopSalesReport;
import com.intermanage.backend.sales.dto.TopSalesReport.Group;
import com.intermanage.backend.sales.dto.TopSalesReport.OtherStore;
import com.intermanage.backend.sales.dto.TopSalesReport.Partner;
import com.intermanage.backend.sales.dto.TopSalesReport.Row;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SaleRepository.ArticleSales;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Topy sprzedażowe: najlepsze artykuły okresu według ilości, marży zł albo marży % w każdej pozycji jednego poziomu
 * nomenklatury albo w całym sklepie, z porównaniem ze sklepami zaprzyjaźnionymi.
 * <p>
 * Top ilościowy pomija artykuły z promocji o marży do {@value #MAX_PROMOTION_MARGIN_PERCENT}%, bo taka ilość to efekt
 * ceny, a nie prawdziwy hit. Topy marży pomijają sprzedaż z zerowym kosztem FIFO: marża 100% jest wtedy fałszywa.
 * Żaden top nie liczy rejonów, które nie są towarem ({@link RegionGroups#isNotGoods}).
 */
@Service
public class TopSalesService {

    static final int MAX_LIMIT = 1000;
    static final int MAX_PROMOTION_MARGIN_PERCENT = 10;
    /** Grupa całego sklepu w obliczeniach. */
    private static final String WHOLE_STORE = "*";
    private static final String UNASSIGNED = "";

    private final SaleRepository sales;
    private final PromotionReportService promotions;
    private final NomenclatureRepository nomenclatures;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;

    public TopSalesService(SaleRepository sales, PromotionReportService promotions,
            NomenclatureRepository nomenclatures, StoreRepository stores, StorePartnerRepository partners) {
        this.sales = sales;
        this.promotions = promotions;
        this.nomenclatures = nomenclatures;
        this.stores = stores;
        this.partners = partners;
    }

    @Transactional(readOnly = true)
    public List<Partner> partners(UUID storeId) {
        return partners.findActive(storeId).stream()
                .filter(pair -> pair.getAcceptedAt() != null)
                .map(pair -> pair.getStore().getId().equals(storeId) ? pair.getPartnerStore() : pair.getStore())
                .filter(Store::isActive)
                .sorted(Comparator.comparing(Store::getNumber))
                .map(store -> new Partner(store.getId(), store.getNumber(), store.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TopSalesReport report(UUID storeId, LocalDate from, LocalDate to, TopSalesMetric metric,
            TopSalesLevel level, int limit, BigDecimal minQuantity, List<String> filter, List<UUID> compareStores,
            UUID rankStore) {
        ReportPeriod.check(from, to);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("Liczba wyników musi być od 1 do " + MAX_LIMIT);
        }
        Set<UUID> storeIds = new LinkedHashSet<>();
        storeIds.add(storeId);
        if (compareStores != null) {
            compareStores.stream().filter(id -> !partners.arePartners(storeId, id) && !id.equals(storeId)).findAny()
                    .ifPresent(id -> {
                        throw ApiException.forbidden("Porównać można tylko sklepy zaprzyjaźnione");
                    });
            storeIds.addAll(compareStores);
        }
        UUID ranked = rankStore == null ? storeId : rankStore;
        if (!storeIds.contains(ranked)) {
            throw ApiException.badRequest("Ranking może być tylko z porównywanego sklepu");
        }

        sales.tuneForReport();
        List<String> codes = filter == null ? List.of() : filter;
        Map<UUID, Standing> standings = new HashMap<>();
        for (UUID id : storeIds) {
            standings.put(id, standing(id, from, to, metric, level, minQuantity, codes));
        }

        Map<UUID, Store> byId = stores.findAllById(storeIds).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));
        List<TopSalesReport.Store> shown = new ArrayList<>();
        shown.add(summary(byId.get(ranked), standings.get(ranked)));
        storeIds.stream().filter(id -> !id.equals(ranked))
                .forEach(id -> shown.add(summary(byId.get(id), standings.get(id))));

        Map<String, String> names = nomenclatures.findAllByStore(ranked).stream()
                .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName));
        Map<UUID, Standing> others = new HashMap<>(standings);
        others.remove(ranked);
        List<Group> groups = standings.get(ranked).groups.entrySet().stream()
                .map(entry -> group(entry.getKey(), entry.getValue(), names, limit, others))
                .toList();
        return new TopSalesReport(from, to, metric, level, limit, minQuantity, MAX_PROMOTION_MARGIN_PERCENT, ranked,
                shown, groups);
    }

    /** Ranking jednego sklepu: grupy w kolejności kodów (bez nomenklatury na końcu), artykuły od najlepszego. */
    private Standing standing(UUID storeId, LocalDate from, LocalDate to, TopSalesMetric metric, TopSalesLevel level,
            BigDecimal minQuantity, List<String> filter) {
        List<ArticleSales> articles = sales.sumArticles(storeId, from, to);
        Set<String> lowMargin = metric == TopSalesMetric.QUANTITY && !articles.isEmpty()
                ? promotions.lowMarginPromotionArticles(storeId, from, to, MAX_PROMOTION_MARGIN_PERCENT)
                : Set.of();

        Map<String, List<ArticleSales>> groups = new TreeMap<>(
                Comparator.comparing(String::isEmpty).thenComparing(Comparator.naturalOrder()));
        int promotionSkipped = 0;
        int noCostSkipped = 0;
        for (ArticleSales article : articles) {
            if (!matches(article.getSubfamilyCode(), filter) || !sold(article, metric, minQuantity)) {
                continue;
            }
            if (lowMargin.contains(article.getItm8())) {
                promotionSkipped++;
            } else if (metric != TopSalesMetric.QUANTITY && article.getCostValue().signum() <= 0) {
                noCostSkipped++;
            } else {
                groups.computeIfAbsent(groupOf(article, level), code -> new ArrayList<>()).add(article);
            }
        }
        Comparator<ArticleSales> best = Comparator.comparing((ArticleSales article) -> value(article, metric))
                .reversed().thenComparing(ArticleSales::getItm8);
        groups.values().forEach(group -> group.sort(best));
        return new Standing(groups, promotionSkipped, noCostSkipped);
    }

    private static boolean matches(String subfamilyCode, List<String> filter) {
        if (RegionGroups.isNotGoods(subfamilyCode)) {
            return false;
        }
        return filter.isEmpty() || (subfamilyCode != null && filter.stream().anyMatch(subfamilyCode::startsWith));
    }

    /** Zwroty (ilość do zera) nie są sprzedażą, a procentu nie ma bez sprzedaży brutto. */
    private static boolean sold(ArticleSales article, TopSalesMetric metric, BigDecimal minQuantity) {
        if (article.getQuantity().signum() <= 0) {
            return false;
        }
        return metric != TopSalesMetric.MARGIN_PERCENT || (article.getSaleValueGross().signum() > 0
                && article.getQuantity().compareTo(minQuantity) >= 0);
    }

    private static String groupOf(ArticleSales article, TopSalesLevel level) {
        if (level == TopSalesLevel.STORE) {
            return WHOLE_STORE;
        }
        String code = article.getSubfamilyCode();
        return code == null || code.length() < level.codeLength() ? UNASSIGNED : code.substring(0, level.codeLength());
    }

    private static BigDecimal value(ArticleSales article, TopSalesMetric metric) {
        return switch (metric) {
            case QUANTITY -> article.getQuantity();
            case MARGIN -> article.getMarginValue();
            case MARGIN_PERCENT -> Percent.of(article.getMarginValue(), article.getSaleValueGross());
        };
    }

    private static TopSalesReport.Store summary(Store store, Standing standing) {
        return new TopSalesReport.Store(store.getId(), store.getNumber(), store.getName(), standing.articleCount(),
                standing.promotionSkipped, standing.noCostSkipped);
    }

    private static Group group(String code, List<ArticleSales> articles, Map<String, String> names, int limit,
            Map<UUID, Standing> others) {
        List<Row> rows = new ArrayList<>();
        for (ArticleSales article : articles.subList(0, Math.min(limit, articles.size()))) {
            List<OtherStore> elsewhere = others.entrySet().stream()
                    .map(entry -> entry.getValue().find(entry.getKey(), article.getItm8()))
                    .filter(Objects::nonNull)
                    .toList();
            BigDecimal net = article.getSaleValueNet();
            rows.add(new Row(rows.size() + 1, article.getItm8(), article.getEan(), article.getName(),
                    article.getBrand(), article.getQuantity(), article.getSaleValueGross(), net,
                    article.getMarginValue(), Percent.of(article.getMarginValue(), article.getSaleValueGross()), elsewhere));
        }
        BigDecimal net = sum(articles, ArticleSales::getSaleValueNet);
        BigDecimal margin = sum(articles, ArticleSales::getMarginValue);
        return new Group(WHOLE_STORE.equals(code) ? null : code, names.get(code), articles.size(),
                sum(articles, ArticleSales::getQuantity), net, margin, Percent.of(margin, sum(articles, ArticleSales::getSaleValueGross)), rows);
    }

    private static BigDecimal sum(List<ArticleSales> articles, Function<ArticleSales, BigDecimal> value) {
        return articles.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final class Standing {

        private final Map<String, List<ArticleSales>> groups;
        private final int promotionSkipped;
        private final int noCostSkipped;
        private final Map<String, OtherPlace> places = new HashMap<>();

        Standing(Map<String, List<ArticleSales>> groups, int promotionSkipped, int noCostSkipped) {
            this.groups = groups;
            this.promotionSkipped = promotionSkipped;
            this.noCostSkipped = noCostSkipped;
            for (List<ArticleSales> group : groups.values()) {
                for (int index = 0; index < group.size(); index++) {
                    places.put(group.get(index).getItm8(), new OtherPlace(index + 1, group.get(index)));
                }
            }
        }

        int articleCount() {
            return places.size();
        }

        OtherStore find(UUID storeId, String itm8) {
            OtherPlace place = places.get(itm8);
            if (place == null) {
                return null;
            }
            ArticleSales article = place.article;
            return new OtherStore(storeId, place.rank, article.getQuantity(), article.getMarginValue(),
                    Percent.of(article.getMarginValue(), article.getSaleValueGross()));
        }
    }

    private record OtherPlace(int rank, ArticleSales article) {
    }
}
