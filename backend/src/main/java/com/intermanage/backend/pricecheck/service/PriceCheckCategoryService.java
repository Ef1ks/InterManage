package com.intermanage.backend.pricecheck.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.pricecheck.dto.PriceCheckCategoryRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckCategoryResponse;
import com.intermanage.backend.pricecheck.entity.PriceCheckCategory;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.PriceCheckCategoryRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Własne kategorie raportu zakupu bazowego poza bazą (Ustawienia sklepu): nazwa i rejony nomenklatury. Raport
 * przypisuje przyjęcie do kategorii po rejonach jego artykułów; usunięcie to znacznik z przywróceniem.
 */
@Service
public class PriceCheckCategoryService {

    private final PriceCheckCategoryRepository categories;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PriceCheckMapper mapper;

    public PriceCheckCategoryService(PriceCheckCategoryRepository categories, StoreRepository stores,
            AppUserRepository users, PriceCheckMapper mapper) {
        this.categories = categories;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<PriceCheckCategoryResponse> list(UUID storeId) {
        return categories.findActive(storeId).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public PriceCheckCategoryResponse create(UUID storeId, PriceCheckCategoryRequest request, UUID userId) {
        String name = request.name().strip();
        requireFreeName(storeId, name, null);
        return mapper.toResponse(categories.saveAndFlush(new PriceCheckCategory(stores.getReferenceById(storeId),
                name, regions(request), user(userId))));
    }

    @Transactional
    public PriceCheckCategoryResponse update(UUID storeId, UUID id, PriceCheckCategoryRequest request, UUID userId) {
        PriceCheckCategory category = active(storeId, id);
        String name = request.name().strip();
        requireFreeName(storeId, name, id);
        category.update(name, regions(request), user(userId));
        categories.flush();
        return mapper.toResponse(category);
    }

    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        PriceCheckCategory category = categories.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kategorii"));
        if (category.getDeletedAt() == null) {
            category.markDeleted(user(userId));
            categories.flush();
        }
    }

    @Transactional
    public PriceCheckCategoryResponse restore(UUID storeId, UUID id) {
        PriceCheckCategory category = categories.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kategorii"));
        if (category.getDeletedAt() != null) {
            requireFreeName(storeId, category.getName(), id);
            category.restore();
            categories.flush();
        }
        return mapper.toResponse(category);
    }

    private PriceCheckCategory active(UUID storeId, UUID id) {
        return categories.findInStore(storeId, id).filter(category -> category.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kategorii"));
    }

    private void requireFreeName(UUID storeId, String name, UUID exceptId) {
        if (categories.existsActiveName(storeId, name, exceptId)) {
            throw ApiException.conflict("Kategoria „" + name + "” już istnieje");
        }
    }

    private static List<String> regions(PriceCheckCategoryRequest request) {
        return request.regionCodes().stream().distinct().sorted().toList();
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
