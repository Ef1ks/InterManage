package com.intermanage.backend.store.service;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.store.dto.StoreFileLocationsResponse;
import com.intermanage.backend.store.entity.StoreFileLocation;
import com.intermanage.backend.store.repository.StoreFileLocationRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ścieżki plików sklepu na dysku sieciowym: w jednym sklepie są takie same na każdym komputerze, więc ustawione raz
 * działają u wszystkich. Rodzaje ścieżek (klucze) wymyśla front; serwer tylko je przechowuje.
 */
@Service
public class StoreFileLocationService {

    private final StoreFileLocationRepository locations;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public StoreFileLocationService(StoreFileLocationRepository locations, StoreRepository stores,
            AppUserRepository users) {
        this.locations = locations;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public StoreFileLocationsResponse get(UUID storeId) {
        Map<String, String> values = new TreeMap<>();
        for (StoreFileLocation location : locations.findByStoreId(storeId)) {
            if (location.getValue() != null) values.put(location.getKey(), location.getValue());
        }
        return new StoreFileLocationsResponse(values);
    }

    @Transactional
    public StoreFileLocationsResponse save(UUID storeId, Map<String, String> changes, UUID userId) {
        Map<String, StoreFileLocation> saved = locations.findByStoreId(storeId).stream()
                .collect(Collectors.toMap(StoreFileLocation::getKey, Function.identity()));
        changes.forEach((key, raw) -> {
            String value = raw == null || raw.isBlank() ? null : raw.trim();
            StoreFileLocation location = saved.get(key);
            if (location == null) {
                if (value == null) return;
                location = new StoreFileLocation(stores.getReferenceById(storeId), key);
            }
            if (location.update(value, users.getReferenceById(userId))) locations.save(location);
        });
        locations.flush();
        return get(storeId);
    }
}
