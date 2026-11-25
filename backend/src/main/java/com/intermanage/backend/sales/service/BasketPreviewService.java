package com.intermanage.backend.sales.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.cadtop.dto.CadPrices;
import com.intermanage.backend.cadtop.service.CadPriceService;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.BasketArticleResponse;
import com.intermanage.backend.sales.dto.BasketPreviewRequest;
import com.intermanage.backend.sales.mapper.SalesMapper;
import com.intermanage.backend.sales.repository.BasketRepository;
import com.intermanage.backend.sales.repository.BasketRepository.Article;
import com.intermanage.backend.sales.repository.BasketRepository.ArticleSupplier;
import com.intermanage.backend.sales.repository.SaleRepository;

/**
 * Wiele artykułów naraz do koszyka zakupowego: dostawcy z ostatniego roku z ceną i ilością z ostatniej dostawy od
 * każdego, a przy artykule z cadencier aktualna cena bazy. Koszyk żyje w przeglądarce, więc niczego nie zapisuje.
 */
@Service
public class BasketPreviewService {

    private static final int SUPPLIER_DAYS = 365;

    private final BasketRepository basket;
    private final CadPriceService cadPrices;
    private final SaleRepository sales;
    private final SalesMapper mapper;

    public BasketPreviewService(BasketRepository basket, CadPriceService cadPrices, SaleRepository sales,
            SalesMapper mapper) {
        this.basket = basket;
        this.cadPrices = cadPrices;
        this.sales = sales;
        this.mapper = mapper;
    }

    /** Artykuły w kolejności z żądania; ITM8 spoza kartoteki jest pomijany. */
    @Transactional(readOnly = true)
    public List<BasketArticleResponse> preview(UUID storeId, BasketPreviewRequest request) {
        List<String> wanted = request.itm8s().stream().map(String::strip).distinct().toList();
        String[] itm8s = wanted.toArray(String[]::new);
        sales.tuneForReport();
        List<Article> articles = basket.findArticles(storeId, itm8s);
        Map<String, List<ArticleSupplier>> suppliers = basket
                .findSuppliers(storeId, LocalDate.now(StoreTime.ZONE).minusDays(SUPPLIER_DAYS).atStartOfDay(), itm8s)
                .stream()
                .collect(Collectors.groupingBy(ArticleSupplier::getItm8));
        CadPrices prices = cadPrices.current(storeId,
                articles.stream().filter(Article::getCadencier).map(Article::getItm8).toList());

        Map<String, Integer> order = new HashMap<>();
        wanted.forEach(itm8 -> order.put(itm8, order.size()));
        return articles.stream()
                .sorted(Comparator.comparing(article -> order.get(article.getItm8())))
                .map(article -> mapper.toBasketArticle(article, prices.of(article.getItm8()),
                        suppliers.getOrDefault(article.getItm8(), List.of())))
                .toList();
    }
}
