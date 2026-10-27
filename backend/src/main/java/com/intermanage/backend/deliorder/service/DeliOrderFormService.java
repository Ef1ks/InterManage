package com.intermanage.backend.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.OrderFormResponse;
import com.intermanage.backend.dto.PasteOrderProductsRequest;
import com.intermanage.backend.dto.PasteOrderProductsResponse;
import com.intermanage.backend.dto.ProductSearchResponse;
import com.intermanage.backend.entity.OrderCategory;
import com.intermanage.backend.entity.OrderSection;
import com.intermanage.backend.entity.OrderUnit;
import com.intermanage.backend.entity.Product;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.mapper.OrderFormMapper;
import com.intermanage.backend.repository.OrderCategoryRepository;
import com.intermanage.backend.repository.OrderSectionRepository;
import com.intermanage.backend.repository.ProductRepository;
import com.intermanage.backend.repository.ProductSearch;
import com.intermanage.backend.repository.StoreRepository;

/**
 * Układanie formularza zamówienia garmażu sklepu: działy (zakładki), kategorie w działach i produkty z kartoteki
 * w kategoriach. Każda zmiana zwraca cały formularz, żeby front od razu pokazał aktualny stan.
 */
@Service
public class DeliOrderFormService {

    /** Wyszukiwarka rusza od tylu znaków - krótsze zapytanie dopasowałoby tysiące produktów. */
    public static final int MIN_SEARCH_LENGTH = 3;
    private static final int SEARCH_LIMIT = 20;

    private final OrderSectionRepository sections;
    private final OrderCategoryRepository categories;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final OrderFormMapper orderFormMapper;

    public DeliOrderFormService(OrderSectionRepository sections, OrderCategoryRepository categories,
            ProductRepository products, StoreRepository stores, OrderFormMapper orderFormMapper) {
        this.sections = sections;
        this.categories = categories;
        this.products = products;
        this.stores = stores;
        this.orderFormMapper = orderFormMapper;
    }

    @Transactional(readOnly = true)
    public OrderFormResponse form(UUID storeId) {
        return new OrderFormResponse(sections.findByStoreId(storeId).stream().map(orderFormMapper::toResponse).toList());
    }

    @Transactional
    public OrderFormResponse createSection(UUID storeId, String name) {
        String trimmed = name.trim();
        ensureSectionNameFree(storeId, trimmed, null);
        sections.save(new OrderSection(stores.getReferenceById(storeId), trimmed, sections.nextPosition(storeId)));
        return current(storeId);
    }

    @Transactional
    public OrderFormResponse renameSection(UUID storeId, UUID sectionId, String name) {
        OrderSection section = loadSection(storeId, sectionId);
        String trimmed = name.trim();
        ensureSectionNameFree(storeId, trimmed, sectionId);
        section.setName(trimmed);
        return current(storeId);
    }

    /** Usuwa dział razem z jego kategoriami i listami produktów (produkty w kartotece zostają). */
    @Transactional
    public OrderFormResponse deleteSection(UUID storeId, UUID sectionId) {
        sections.delete(loadSection(storeId, sectionId));
        return current(storeId);
    }

    @Transactional
    public OrderFormResponse createCategory(UUID storeId, UUID sectionId, String name) {
        OrderSection section = loadSection(storeId, sectionId);
        String trimmed = name.trim();
        ensureCategoryNameFree(section, trimmed, null);
        section.addCategory(trimmed);
        return current(storeId);
    }

    @Transactional
    public OrderFormResponse renameCategory(UUID storeId, UUID categoryId, String name) {
        OrderCategory category = loadCategory(storeId, categoryId);
        String trimmed = name.trim();
        ensureCategoryNameFree(category.getSection(), trimmed, categoryId);
        category.setName(trimmed);
        return current(storeId);
    }

    @Transactional
    public OrderFormResponse deleteCategory(UUID storeId, UUID categoryId) {
        OrderCategory category = loadCategory(storeId, categoryId);
        category.getSection().getCategories().remove(category);
        return current(storeId);
    }

    @Transactional
    public OrderFormResponse addProduct(UUID storeId, UUID categoryId, UUID productId) {
        OrderCategory category = loadCategory(storeId, categoryId);
        Product product = products.findByIdAndStoreId(productId, storeId)
                .orElseThrow(() -> ApiException.badRequest("Nie ma takiego produktu w kartotece tego sklepu"));
        category.addProduct(product);
        return current(storeId);
    }

    /**
     * Dodaje produkty wklejone z Excela po bieżącym EAN-ie z kartoteki sklepu, w kolejności wklejenia. Produkt, który
     * już jest w kategorii, zostaje na swoim miejscu i dostaje wklejoną jednostkę. Nieznane EAN-y nie blokują reszty,
     * wracają w odpowiedzi.
     */
    @Transactional
    public PasteOrderProductsResponse pasteProducts(UUID storeId, UUID categoryId,
            List<PasteOrderProductsRequest.Row> rows) {
        OrderCategory category = loadCategory(storeId, categoryId);
        List<String> eans = rows.stream().map(row -> row.ean().trim()).toList();
        // Ten sam EAN bywa przy kilku ITM8 (stary i nowy kod) - bierzemy aktywny.
        Map<String, Product> byEan = new HashMap<>();
        for (Product product : products.findByStoreIdAndEanIn(storeId, eans)) {
            byEan.merge(product.getEan(), product,
                    (current, other) -> Boolean.TRUE.equals(current.getActive()) ? current : other);
        }

        int added = 0;
        int updated = 0;
        List<String> notFound = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Product product = byEan.get(eans.get(i));
            if (product == null) {
                notFound.add(eans.get(i));
                continue;
            }
            boolean present = category.getProducts().stream()
                    .anyMatch(item -> item.getProduct().getId().equals(product.getId()));
            category.addProduct(product).setOrderUnit(rows.get(i).orderUnit());
            if (present) {
                updated++;
            } else {
                added++;
            }
        }
        return new PasteOrderProductsResponse(current(storeId), added, updated, notFound.stream().distinct().toList());
    }

    /** Czy produkt zamawia się w kilogramach, czy w sztukach. Złożone zamówienia zachowują swoją jednostkę. */
    @Transactional
    public OrderFormResponse setOrderUnit(UUID storeId, UUID categoryId, UUID itemId, OrderUnit orderUnit) {
        loadCategory(storeId, categoryId).findProduct(itemId)
                .orElseThrow(() -> ApiException.notFound("Tego produktu nie ma w kategorii"))
                .setOrderUnit(orderUnit);
        return current(storeId);
    }

    @Transactional
    public OrderFormResponse removeProduct(UUID storeId, UUID categoryId, UUID itemId) {
        if (!loadCategory(storeId, categoryId).removeProduct(itemId)) {
            throw ApiException.notFound("Tego produktu nie ma w kategorii");
        }
        return current(storeId);
    }

    @Transactional(readOnly = true)
    public List<ProductSearchResponse> searchProducts(UUID storeId, String query) {
        List<String> words = ProductSearch.words(query);
        if (String.join("", words).length() < MIN_SEARCH_LENGTH) {
            throw ApiException.badRequest("Wpisz co najmniej " + MIN_SEARCH_LENGTH + " znaki");
        }
        return products.findBy(ProductSearch.inStoreMatching(storeId, words),
                        q -> q.sortBy(ProductSearch.ORDER).limit(SEARCH_LIMIT).all())
                .stream()
                .map(orderFormMapper::toSearchResult)
                .toList();
    }

    /** Formularz po zmianie: zapis do bazy nadaje id nowym kategoriom i pozycjom, zanim trafią do odpowiedzi. */
    private OrderFormResponse current(UUID storeId) {
        sections.flush();
        return form(storeId);
    }

    private OrderSection loadSection(UUID storeId, UUID sectionId) {
        return sections.findByIdAndStoreId(sectionId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu"));
    }

    private OrderCategory loadCategory(UUID storeId, UUID categoryId) {
        return categories.findInStore(categoryId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kategorii"));
    }

    private void ensureSectionNameFree(UUID storeId, String name, UUID excludedId) {
        boolean taken = sections.findByStoreId(storeId).stream()
                .anyMatch(s -> !Objects.equals(s.getId(), excludedId) && s.getName().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.conflict("Dział „" + name + "” już istnieje");
        }
    }

    private static void ensureCategoryNameFree(OrderSection section, String name, UUID excludedId) {
        boolean taken = section.getCategories().stream()
                .anyMatch(c -> !Objects.equals(c.getId(), excludedId) && c.getName().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.conflict("Kategoria „" + name + "” już jest w tym dziale");
        }
    }
}
