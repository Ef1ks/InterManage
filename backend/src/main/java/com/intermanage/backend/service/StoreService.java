package com.intermanage.backend.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.StoreRequest;
import com.intermanage.backend.dto.StoreResponse;
import com.intermanage.backend.dto.StoreSummary;
import com.intermanage.backend.entity.AppUser;
import com.intermanage.backend.entity.Store;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.mapper.StoreMapper;
import com.intermanage.backend.repository.StoreRepository;

@Service
public class StoreService {

    private final StoreRepository stores;
    private final StoreMapper storeMapper;

    public StoreService(StoreRepository stores, StoreMapper storeMapper) {
        this.stores = stores;
        this.storeMapper = storeMapper;
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> list() {
        Map<UUID, Long> counts = userCounts();
        return stores.findAllOrdered().stream()
                .map(store -> storeMapper.toResponse(store, counts.getOrDefault(store.getId(), 0L)))
                .toList();
    }

    @Transactional
    public StoreResponse create(StoreRequest request) {
        String number = request.number().trim();
        if (stores.existsByNumber(number)) {
            throw ApiException.conflict("Sklep o numerze " + number + " już istnieje");
        }
        Store store = new Store(number, request.name().trim(), blankToNull(request.address()));
        store.setActive(request.active());
        return storeMapper.toResponse(stores.save(store), 0);
    }

    @Transactional
    public StoreResponse update(UUID id, StoreRequest request) {
        Store store = stores.findById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String number = request.number().trim();
        if (stores.existsByNumberAndIdNot(number, id)) {
            throw ApiException.conflict("Sklep o numerze " + number + " już istnieje");
        }
        store.setNumber(number);
        store.setName(request.name().trim());
        store.setAddress(blankToNull(request.address()));
        store.setActive(request.active());
        return storeMapper.toResponse(store, userCounts().getOrDefault(id, 0L));
    }

    /** Sklepy do wyboru: właściciel widzi wszystkie aktywne, pracownik tylko przypisane aktywne. */
    @Transactional(readOnly = true)
    public List<StoreSummary> accessibleStores(AppUser user) {
        List<Store> result = user.getRole().isOwner()
                ? stores.findActiveOrdered()
                : user.getStores().stream()
                        .filter(Store::isActive)
                        .sorted(Comparator.comparing(Store::getNumber))
                        .toList();
        return result.stream().map(storeMapper::toSummary).toList();
    }

    /** Sklepy z listy id z formularza; nieistniejące id to błąd. */
    @Transactional(readOnly = true)
    public List<Store> loadAll(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Store> found = stores.findAllById(ids);
        if (found.size() != ids.stream().distinct().count()) {
            throw ApiException.badRequest("Wybrany sklep nie istnieje");
        }
        return found;
    }

    private Map<UUID, Long> userCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : stores.countUsersPerStore()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
