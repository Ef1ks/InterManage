package com.intermanage.backend.store.service;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.dto.StoreFileLocationsResponse;
import com.intermanage.backend.store.entity.StoreFileLocation;
import com.intermanage.backend.store.repository.StoreFileLocationRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ścieżki plików sklepu na dysku sieciowym: w jednym sklepie są takie same na każdym komputerze, więc ustawione raz
 * działają u wszystkich. Rodzaje ścieżek (klucze) wymyśla front; serwer tylko je przechowuje.
 *
 * <p>Ścieżki folderów ({@code folder:<klucz>}) ma z góry aplikacja ({@link DefaultFolders}, wspólne dla wszystkich
 * sklepów); wpis sklepu to jego własna ścieżka, tylko dla niego. Odczyt daje własną albo domyślną, a zapis ścieżki
 * równej domyślnej albo pustej przywraca domyślną.
 */
@Service
public class StoreFileLocationService {

    private final StoreFileLocationRepository locations;
    private final StoreRepository stores;
    private final AppUserRepository users;

    private static final String FOLDER_PREFIX = "folder:";

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
        // Foldery bez własnej ścieżki sklepu mają domyślną ścieżkę aplikacji (DefaultFolders). Folder plików PROTO
        // i folder nowych artykułów są w ustawieniach plików PROTO.
        for (String base : DefaultFolders.ORDER) {
            if (base.equals(DefaultFolders.PROTO) || base.equals(DefaultFolders.PRODUCT_FILE)) continue;
            values.putIfAbsent(FOLDER_PREFIX + DefaultFolders.key(base, storeId), DefaultFolders.PATHS.get(base));
        }
        return new StoreFileLocationsResponse(values);
    }

    /** Własna ścieżka sklepu dla folderu ({@code null} = domyślna aplikacji). */
    @Transactional(readOnly = true)
    public String custom(UUID storeId, String folderKey) {
        return locations.findByStoreId(storeId).stream()
                .filter(location -> location.getKey().equals(FOLDER_PREFIX + folderKey))
                .map(StoreFileLocation::getValue).findFirst().orElse(null);
    }

    @Transactional
    public StoreFileLocationsResponse save(UUID storeId, Map<String, String> changes, UUID userId) {
        Map<String, StoreFileLocation> saved = locations.findByStoreId(storeId).stream()
                .collect(Collectors.toMap(StoreFileLocation::getKey, Function.identity()));
        changes.forEach((key, raw) -> {
            String value = raw == null || raw.isBlank() ? null : raw.trim();
            // Ścieżka równa domyślnej to domyślna: sklep nie ma wtedy własnej (zmiana domyślnej obejmie i jego).
            if (value != null && key.startsWith(FOLDER_PREFIX)
                    && DefaultFolders.same(value, DefaultFolders.of(key.substring(FOLDER_PREFIX.length())))) {
                value = null;
            }
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
