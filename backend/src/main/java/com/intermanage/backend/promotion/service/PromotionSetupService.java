package com.intermanage.backend.promotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.promotion.dto.PromotionSetupArticle;
import com.intermanage.backend.promotion.repository.PromotionSetupRepository;
import com.intermanage.backend.promotion.repository.PromotionSetupRepository.ArticlePrices;

/** Ustawianie promocji: artykuły po wpisanych kodach (jak we wprowadzaniu danych) z ceną zwykłą i ostatnim zakupem. */
@Service
public class PromotionSetupService {

    private final DataEntryService dataEntry;
    private final PromotionSetupRepository repository;

    public PromotionSetupService(DataEntryService dataEntry, PromotionSetupRepository repository) {
        this.dataEntry = dataEntry;
        this.repository = repository;
    }

    /** Artykuły dla kodów: EAN, multikod, kod z etykiety wagi albo ITM8; nieznane kody pomija. */
    @Transactional(readOnly = true)
    public List<PromotionSetupArticle> articles(UUID storeId, Collection<String> codes) {
        List<CatalogProduct> products = dataEntry.lookup(storeId, codes, true);
        List<String> itm8s = products.stream().map(CatalogProduct::itm8).filter(Objects::nonNull).distinct().toList();
        Map<String, ArticlePrices> prices = itm8s.isEmpty() ? Map.of()
                : repository.findPrices(storeId, itm8s, LocalDate.now(StoreTime.ZONE)).stream()
                        .collect(Collectors.toMap(ArticlePrices::getItm8, Function.identity()));
        return products.stream().map(product -> article(product, prices.get(product.itm8()))).toList();
    }

    /** Cena zwykła i ostatni zakup jednego artykułu po ITM8 (okno przeceny na karcie artykułu); {@code null} spoza kartoteki. */
    @Transactional(readOnly = true)
    public MarkdownPrices markdownPrices(UUID storeId, String itm8) {
        return repository.findPrices(storeId, List.of(itm8), LocalDate.now(StoreTime.ZONE)).stream().findFirst()
                .map(prices -> new MarkdownPrices(prices.getRegularPrice(), prices.getLastPurchasePrice(),
                        prices.getLastPurchaseDate() != null ? prices.getLastPurchaseDate().toLocalDate() : null))
                .orElse(null);
    }

    /** Ceny do okna przeceny; {@code lastPurchasePrice} pusta, gdy artykuł nie miał przyjęcia PZ. */
    public record MarkdownPrices(BigDecimal regularPrice, BigDecimal lastPurchasePrice, LocalDate lastPurchaseDate) {
    }

    private static PromotionSetupArticle article(CatalogProduct product, ArticlePrices prices) {
        boolean delivered = prices != null && prices.getLastPurchasePrice() != null;
        return new PromotionSetupArticle(product.ean(), product.itm8(), product.catalogEan(), product.name(),
                product.brand(), product.vatPercent(), prices != null ? prices.getRegularPrice() : product.salePrice(),
                delivered ? prices.getLastPurchasePrice() : product.purchasePrice(),
                delivered ? prices.getLastPurchaseDate().toLocalDate() : null, product.draft());
    }
}
