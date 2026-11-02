package com.intermanage.backend.product.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductDraft;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.product.repository.ProductDraftRepository;

/**
 * Szkice artykułów z potwierdzonych plików CADBEZP przy imporcie kartoteki. Rozstrzyga je tylko raport wygenerowany
 * po potwierdzeniu (poranny raport wgrany po południu nie zna jeszcze artykułów założonych w ciągu dnia): artykuł z
 * EAN-em szkicu w raporcie dopasowuje szkic do produktu z kartoteki, brak artykułu usuwa szkic (znacznik).
 */
@Service
public class ProductDraftService {

    /** Czas w nagłówku raportu jest lokalny, jak w systemie sklepu. */
    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");

    private final ProductDraftRepository drafts;

    public ProductDraftService(ProductDraftRepository drafts) {
        this.drafts = drafts;
    }

    /** Wynik dla podsumowania importu. */
    public record Result(int matched, int removed) {
    }

    /**
     * W transakcji importu, po zapisie nowych produktów. {@code reportByEan}: artykuły z raportu po EAN-ie bez zer
     * wiodących.
     */
    public Result resolve(ProductImport productImport, Map<String, Product> reportByEan) {
        Instant reportTime = productImport.getReportGeneratedAt() != null
                ? productImport.getReportGeneratedAt().atZone(STORE_ZONE).toInstant()
                : productImport.getImportedAt();
        int matched = 0;
        int removed = 0;
        for (ProductDraft draft : drafts.findPending(productImport.getStore().getId())) {
            if (!draft.getCreatedAt().isBefore(reportTime)) {
                continue;
            }
            Product product = reportByEan.get(draft.getEan());
            if (product != null) {
                draft.matched(product, productImport);
                matched++;
            } else {
                draft.removed(ProductDraft.RemovedReason.MISSING, productImport, null);
                removed++;
            }
        }
        return new Result(matched, removed);
    }
}
