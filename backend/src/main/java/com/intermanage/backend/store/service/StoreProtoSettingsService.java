package com.intermanage.backend.store.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.dto.ProtoSettingsRequest;
import com.intermanage.backend.store.dto.ProtoSettingsResponse;
import com.intermanage.backend.store.entity.StoreProtoSettings;
import com.intermanage.backend.store.mapper.StoreMapper;
import com.intermanage.backend.store.repository.StoreProtoSettingsRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/** Folder plików PROTO sklepu, protokoły ukryte w oknie generowania i folder pliku nowych artykułów. */
@Service
public class StoreProtoSettingsService {

    /** Folder, z którego program sklepu wczytuje pliki, gdy sklep nie ustawił innego (skrót na dysku N). */
    public static final String DEFAULT_FOLDER = "N:\\AVISOCONFIG\\BACKUP";

    private final StoreProtoSettingsRepository settings;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final StoreMapper mapper;

    public StoreProtoSettingsService(StoreProtoSettingsRepository settings, StoreRepository stores,
            AppUserRepository users, StoreMapper mapper) {
        this.settings = settings;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    /** Ustawienia sklepu; bez zapisanych: domyślny folder i wszystkie protokoły widoczne (nic nie zapisuje). */
    @Transactional(readOnly = true)
    public StoreProtoSettings find(UUID storeId) {
        return settings.findByStoreId(storeId).orElseGet(() -> new StoreProtoSettings(stores.getReferenceById(storeId)));
    }

    @Transactional(readOnly = true)
    public ProtoSettingsResponse get(UUID storeId) {
        return toResponse(find(storeId));
    }

    @Transactional
    public ProtoSettingsResponse save(UUID storeId, ProtoSettingsRequest request, UUID userId) {
        StoreProtoSettings saved = find(storeId);
        if (saved.update(custom(request.folderPath(), DEFAULT_FOLDER),
                custom(request.productFileFolderPath(), DefaultFolders.PATHS.get(DefaultFolders.PRODUCT_FILE)),
                request.hiddenProtocols(), users.getReferenceById(userId))) {
            settings.saveAndFlush(saved);
        }
        return get(storeId);
    }

    /** Własna ścieżka sklepu; pusta albo równa domyślnej aplikacji = domyślna ({@code null}). */
    private static String custom(String path, String defaultPath) {
        String value = path == null || path.isBlank() ? null : path.trim();
        return DefaultFolders.same(value, defaultPath) ? null : value;
    }

    private ProtoSettingsResponse toResponse(StoreProtoSettings value) {
        boolean defaultFolder = value.getFolderPath() == null;
        String productFile = value.getProductFileFolder() != null ? value.getProductFileFolder()
                : DefaultFolders.PATHS.get(DefaultFolders.PRODUCT_FILE);
        return mapper.toProtoSettingsResponse(value, defaultFolder ? DEFAULT_FOLDER : value.getFolderPath(), defaultFolder,
                productFile);
    }
}
