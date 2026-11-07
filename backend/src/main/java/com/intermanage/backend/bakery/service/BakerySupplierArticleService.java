package com.intermanage.backend.bakery.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.bakery.dto.BakerySupplierArticles;
import com.intermanage.backend.bakery.entity.BakeryHiddenArticle;
import com.intermanage.backend.bakery.mapper.BakeryMapper;
import com.intermanage.backend.bakery.repository.BakeryCatalogRepository;
import com.intermanage.backend.bakery.repository.BakeryHiddenArticleRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Artykuły dostawcy na listach WZ. Kartoteka trzyma też towar, który w danych tygodniach nie przyjeżdża, więc dla każdego
 * dostawcy osobno da się odznaczyć artykuły, których nie chcemy widzieć domyślnie. Zapamiętujemy odznaczone, dzięki czemu
 * nowy artykuł z kartoteki sam trafia na WZ.
 */
@Service
public class BakerySupplierArticleService {

    private static final int MIN_SUPPLIER_NAME = 3;

    private final BakeryCatalogRepository catalog;
    private final BakeryHiddenArticleRepository hidden;
    private final BakeryMapper mapper;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;

    public BakerySupplierArticleService(BakeryCatalogRepository catalog, BakeryHiddenArticleRepository hidden,
            BakeryMapper mapper, SupplierRepository suppliers, AppUserRepository users) {
        this.catalog = catalog;
        this.hidden = hidden;
        this.mapper = mapper;
        this.suppliers = suppliers;
        this.users = users;
    }

    /** Aktywne artykuły dostawcy z kartoteki, bez odznaczonych. */
    public List<Product> shownInCatalog(UUID storeId, Supplier supplier) {
        Set<UUID> hiddenIds = hidden.findActive(supplier.getId()).stream()
                .map(article -> article.getProduct().getId()).collect(Collectors.toSet());
        return inCatalog(storeId, supplier).stream().filter(product -> !hiddenIds.contains(product.getId())).toList();
    }

    @Transactional(readOnly = true)
    public BakerySupplierArticles get(UUID storeId, UUID supplierId) {
        return response(storeId, supplier(storeId, supplierId));
    }

    /** Zastępuje listę odznaczonych. */
    @Transactional
    public BakerySupplierArticles update(UUID storeId, UUID supplierId, List<UUID> productIds, UUID userId) {
        Supplier supplier = supplier(storeId, supplierId);
        Set<UUID> wanted = new HashSet<>(productIds);
        Map<UUID, Product> found = wanted.isEmpty() ? Map.of()
                : catalog.findInStore(storeId, wanted).stream()
                        .collect(Collectors.toMap(Product::getId, product -> product));
        if (found.size() != wanted.size()) {
            throw ApiException.badRequest("Nie znaleziono artykułu w kartotece sklepu");
        }
        AppUser user = users.getReferenceById(userId);
        for (BakeryHiddenArticle article : hidden.findActive(supplierId)) {
            if (!wanted.remove(article.getProduct().getId())) {
                article.remove(user);
            }
        }
        wanted.forEach(id -> hidden.save(new BakeryHiddenArticle(supplier, found.get(id), user)));
        hidden.flush();
        return response(storeId, supplier);
    }

    /** Odznaczone są na liście, nawet gdy zniknęły z kartoteki dostawcy: tak da się je zaznaczyć z powrotem. */
    private BakerySupplierArticles response(UUID storeId, Supplier supplier) {
        List<BakeryHiddenArticle> hiddenArticles = hidden.findActive(supplier.getId());
        Map<UUID, Product> all = new HashMap<>();
        inCatalog(storeId, supplier).forEach(product -> all.put(product.getId(), product));
        hiddenArticles.forEach(article -> all.put(article.getProduct().getId(), article.getProduct()));
        List<Product> sorted = new ArrayList<>(all.values());
        sorted.sort(Comparator.comparing(Product::getName, Comparator.nullsLast(Comparator.naturalOrder())));
        return new BakerySupplierArticles(sorted.stream().map(mapper::toArticle).toList(),
                hiddenArticles.stream().map(article -> article.getProduct().getId()).toList());
    }

    private List<Product> inCatalog(UUID storeId, Supplier supplier) {
        String supplierName = DataEntryLinkService.code(supplier.getName());
        // products.supplier to nazwa ucięta do 30 znaków i czasem bez polskich liter.
        List<String> names = catalog.findSupplierNames(storeId).stream()
                .filter(name -> {
                    String plain = DataEntryLinkService.code(name);
                    return plain.length() >= MIN_SUPPLIER_NAME && supplierName.startsWith(plain);
                }).toList();
        return names.isEmpty() ? List.of() : catalog.findActiveOfSuppliers(storeId, names);
    }

    private Supplier supplier(UUID storeId, UUID id) {
        return suppliers.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.badRequest("Wybierz dostawcę z listy dostawców sklepu"));
    }
}
