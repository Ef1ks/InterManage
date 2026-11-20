package com.intermanage.backend.store.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.dto.FolderPathResponse;
import com.intermanage.backend.store.entity.StoreProtoSettings;

/**
 * Stan ścieżek folderów sklepu do strony „Ścieżki folderów”: domyślna aplikacji albo własna sklepu. Samą ścieżkę widzi
 * tylko właściciel; pozostali dostają {@code folder}, czyli skrót ścieżki, po którym strona łączy rodzaje plików
 * z tym samym folderem w jeden wiersz, nie znając ścieżki.
 */
@Service
public class FolderPathService {

    private final StoreFileLocationService locations;
    private final StoreProtoSettingsService protoSettings;

    public FolderPathService(StoreFileLocationService locations, StoreProtoSettingsService protoSettings) {
        this.locations = locations;
        this.protoSettings = protoSettings;
    }

    @Transactional(readOnly = true)
    public List<FolderPathResponse> list(UUID storeId, boolean showPaths) {
        StoreProtoSettings proto = protoSettings.find(storeId);
        List<FolderPathResponse> result = new ArrayList<>();
        for (String base : DefaultFolders.ORDER) {
            String key = DefaultFolders.key(base, storeId);
            String own = switch (base) {
                case DefaultFolders.PROTO -> proto.getFolderPath();
                case DefaultFolders.PRODUCT_FILE -> proto.getProductFileFolder();
                default -> locations.custom(storeId, key);
            };
            String defaultPath = DefaultFolders.PATHS.get(base);
            boolean custom = own != null && !DefaultFolders.same(own, defaultPath);
            String effective = custom ? own : defaultPath;
            result.add(new FolderPathResponse(key, custom, fingerprint(effective), showPaths ? effective : null,
                    showPaths ? defaultPath : null));
        }
        return result;
    }

    /** Krótki skrót ścieżki (bez wielkości liter i końcowego ukośnika): ten sam folder = ten sam skrót. */
    private static String fingerprint(String path) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(DefaultFolders.normalized(path).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
