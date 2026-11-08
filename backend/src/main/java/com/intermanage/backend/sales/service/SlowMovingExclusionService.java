package com.intermanage.backend.sales.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.sales.dto.SlowMovingExcludedItem;
import com.intermanage.backend.sales.entity.SlowMovingExcludedArticle;
import com.intermanage.backend.sales.mapper.SlowMovingExcludedMapper;
import com.intermanage.backend.sales.repository.SlowMovingExcludedArticleRepository;

/** Artykuły, których sklep nie chce widzieć w raporcie niesprzedających się, np. towar sezonowy. */
@Service
public class SlowMovingExclusionService {

    private final SlowMovingExcludedArticleRepository excluded;
    private final ProductRepository products;
    private final AppUserRepository users;
    private final SlowMovingExcludedMapper mapper;

    public SlowMovingExclusionService(SlowMovingExcludedArticleRepository excluded, ProductRepository products,
            AppUserRepository users, SlowMovingExcludedMapper mapper) {
        this.excluded = excluded;
        this.products = products;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SlowMovingExcludedItem> list(UUID storeId) {
        return excluded.findActive(storeId).stream().map(mapper::toItem).toList();
    }

    /** Artykuł już pominięty zostaje z pierwszym wpisem, więc drugie kliknięcie niczego nie psuje. */
    @Transactional
    public SlowMovingExcludedItem exclude(UUID storeId, String itm8, UUID userId) {
        Product product = products.findByStoreIdAndItm8(storeId, itm8)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu w kartotece sklepu"));
        return mapper.toItem(excluded.findActiveByProduct(product.getId()).orElseGet(() -> excluded.save(
                new SlowMovingExcludedArticle(product, users.findById(userId).orElseThrow()))));
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID userId) {
        excluded.findActiveInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu na liście pomijanych"))
                .remove(users.getReferenceById(userId));
    }
}
