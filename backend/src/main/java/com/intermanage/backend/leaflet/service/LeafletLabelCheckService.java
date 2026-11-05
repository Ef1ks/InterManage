package com.intermanage.backend.leaflet.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.leaflet.dto.LeafletLabelCheck;
import com.intermanage.backend.leaflet.dto.LeafletLabelCheck.Status;
import com.intermanage.backend.leaflet.entity.PromoLeafletLine;
import com.intermanage.backend.leaflet.mapper.LeafletMapper;
import com.intermanage.backend.leaflet.repository.PromoLeafletLineRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.product.repository.ProductSearch;

/**
 * Sprawdzarka etykiet z gazetki: po zeskanowaniu EAN-u albo wpisaniu nazwy, marki czy ITM8 mówi, jaką etykietę (kolumna
 * OZNACZENIE PRZY PÓŁCE z pliku gazetki) ma artykuł i od kiedy do kiedy obowiązuje. Obecna gazetka to nie jeden numer:
 * pozycje dwóch gazetek trwają równolegle, a część startuje później, więc status liczymy z dat pozycji. Szukamy
 * w kartotece i w tekście gazetki, bo nazwy się różnią, a EAN z gazetki nie zawsze ma artykuł.
 */
@Service
public class LeafletLabelCheckService {

    private static final int MIN_QUERY_LENGTH = 3;
    /** Tyle artykułów z kartoteki sprawdzamy w gazetkach: wyniki idą od będących w gazetce, nie od alfabetu. */
    private static final int CATALOG_CANDIDATES = 200;
    private static final int TEXT_LINES = 200;
    private static final int RESULT_LIMIT = 30;

    /** Trwające i przyszłe od najwcześniejszego początku, zakończone od najpóźniejszego końca. */
    private static final Comparator<LeafletLabelCheck.Line> LINE_ORDER = Comparator
            .comparing(LeafletLabelCheck.Line::status)
            .thenComparingLong(line -> line.status() == Status.PAST ? -line.dateTo().toEpochDay()
                    : line.dateFrom().toEpochDay());

    private static final Comparator<LeafletLabelCheck.Article> ARTICLE_ORDER = Comparator
            .comparing(LeafletLabelCheck.Article::status)
            .thenComparing(article -> article.lines().isEmpty() ? LocalDate.MIN : article.lines().getFirst().dateTo(),
                    Comparator.reverseOrder())
            .thenComparing(LeafletLabelCheck.Article::name);

    private final PromoLeafletLineRepository lines;
    private final ProductRepository products;
    private final DataEntryService dataEntry;
    private final LeafletService leaflets;
    private final LeafletMapper mapper;

    public LeafletLabelCheckService(PromoLeafletLineRepository lines, ProductRepository products,
            DataEntryService dataEntry, LeafletService leaflets, LeafletMapper mapper) {
        this.lines = lines;
        this.products = products;
        this.dataEntry = dataEntry;
        this.leaflets = leaflets;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public LeafletLabelCheck check(UUID storeId, String query) {
        String text = plain(query);
        List<String> words = ProductSearch.words(text);
        if (String.join("", words).length() < MIN_QUERY_LENGTH) {
            throw ApiException.badRequest("Wpisz co najmniej " + MIN_QUERY_LENGTH + " znaki");
        }
        boolean code = words.size() == 1 && words.getFirst().matches("\\d{8,14}");
        Set<String> itm8s = new LinkedHashSet<>();
        if (code) {
            // Kod z etykiety wagi zna tylko wyszukiwanie po kodzie, nie wyszukiwarka kartoteki.
            dataEntry.lookup(storeId, words, true).stream().map(CatalogProduct::itm8).filter(Objects::nonNull)
                    .forEach(itm8s::add);
        }
        Map<String, List<PromoLeafletLine>> unknown = fromLeafletText(storeId,
                code ? LeafletService.key(text) : text, itm8s);
        products.findBy(ProductSearch.inStoreMatching(storeId, words),
                q -> q.sortBy(ProductSearch.ORDER).limit(CATALOG_CANDIDATES).all())
                .forEach(product -> itm8s.add(product.getItm8()));

        LocalDate today = LocalDate.now(StoreTime.ZONE);
        List<LeafletLabelCheck.Article> articles = new ArrayList<>(articles(storeId, itm8s, today));
        for (List<PromoLeafletLine> group : unknown.values()) {
            List<LeafletLabelCheck.Line> shown = shown(group, today);
            articles.add(mapper.toLabelArticle(group.getFirst(), status(shown), shown));
        }
        articles.sort(ARTICLE_ORDER);
        return new LeafletLabelCheck(articles.size(), articles.stream().limit(RESULT_LIMIT).toList());
    }

    /**
     * Pozycje gazetek pasujące tekstem: artykuły z kartoteki dopisuje do {@code itm8s}, a pozycje, których EAN-u
     * kartoteka nie zna (albo zna tylko ze szkicu, bez ITM8), zwraca po kluczu EAN-u.
     */
    private Map<String, List<PromoLeafletLine>> fromLeafletText(UUID storeId, String text, Set<String> itm8s) {
        List<UUID> ids = lines.findMatching(storeId, DataEntryService.likePatterns(text, true), TEXT_LINES);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<PromoLeafletLine> found = lines.findWithLeaflet(ids);
        Map<String, CatalogProduct> resolved = leaflets.articles(storeId,
                found.stream().map(line -> LeafletService.key(line.getEan())).toList());
        Map<String, List<PromoLeafletLine>> unknown = new LinkedHashMap<>();
        for (PromoLeafletLine line : found) {
            String key = LeafletService.key(line.getEan());
            CatalogProduct product = resolved.get(key);
            if (product != null && product.itm8() != null) {
                itm8s.add(product.itm8());
            } else {
                unknown.computeIfAbsent(key.isEmpty() ? line.getName() : key, ignored -> new ArrayList<>()).add(line);
            }
        }
        return unknown;
    }

    private List<LeafletLabelCheck.Article> articles(UUID storeId, Collection<String> itm8s, LocalDate today) {
        if (itm8s.isEmpty()) {
            return List.of();
        }
        List<PromoLeafletLineRepository.ArticleLine> found = lines.findArticleLines(storeId,
                itm8s.toArray(String[]::new));
        Map<UUID, PromoLeafletLine> byId = lines
                .findWithLeaflet(found.stream().map(PromoLeafletLineRepository.ArticleLine::getLineId).toList())
                .stream().collect(Collectors.toMap(PromoLeafletLine::getId, Function.identity()));
        Map<String, List<PromoLeafletLine>> byArticle = found.stream()
                .collect(Collectors.groupingBy(PromoLeafletLineRepository.ArticleLine::getItm8,
                        Collectors.mapping(row -> byId.get(row.getLineId()), Collectors.toList())));
        return products.findByStoreIdAndItm8In(storeId, itm8s).stream().map(product -> {
            List<LeafletLabelCheck.Line> shown = shown(byArticle.getOrDefault(product.getItm8(), List.of()), today);
            return mapper.toLabelArticle(product, status(shown), shown);
        }).toList();
    }

    /** Trwające i przyszłe promocje, bez trwającej też ostatnia zakończona; pozycje różniące się tylko EAN-em raz. */
    private List<LeafletLabelCheck.Line> shown(List<PromoLeafletLine> group, LocalDate today) {
        List<LeafletLabelCheck.Line> all = group.stream().map(line -> mapper.toLabelLine(line, status(line, today)))
                .distinct().sorted(LINE_ORDER).toList();
        List<LeafletLabelCheck.Line> shown = new ArrayList<>(
                all.stream().filter(line -> line.status() != Status.PAST).toList());
        if (shown.stream().noneMatch(line -> line.status() == Status.CURRENT)) {
            all.stream().filter(line -> line.status() == Status.PAST).findFirst().ifPresent(shown::add);
        }
        return shown;
    }

    private static Status status(PromoLeafletLine line, LocalDate today) {
        if (line.getDateTo().isBefore(today)) {
            return Status.PAST;
        }
        return line.getDateFrom().isAfter(today) ? Status.UPCOMING : Status.CURRENT;
    }

    private static Status status(List<LeafletLabelCheck.Line> shown) {
        return shown.isEmpty() ? Status.NONE : shown.getFirst().status();
    }

    /** Zapytanie bez polskich znaków, bo kartoteka ich nie ma. */
    private static String plain(String query) {
        String text = query == null ? "" : query.strip().toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
