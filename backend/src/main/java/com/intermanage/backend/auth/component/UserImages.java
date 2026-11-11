package com.intermanage.backend.auth.component;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.common.component.ObjectStorage;
import com.intermanage.backend.common.component.ScanFiles;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Zdjęcia kont (tło aplikacji, zdjęcie profilowe): sprawdzenie, że to zdjęcie, i zapis w R2 pod losowym kluczem.
 * Bez połączenia z R2 (lokalnie, w testach) treść zostaje w bazie i serwuje ją backend.
 */
@Component
public class UserImages {

    private static final Logger log = LoggerFactory.getLogger(UserImages.class);

    /** Przyjęte zdjęcie: albo klucz w R2, albo treść do zapisania w bazie. */
    public record Stored(String fileName, String contentType, int sizeBytes, String storageKey, byte[] content) {
    }

    private final ObjectStorage storage;

    public UserImages(ObjectStorage storage) {
        this.storage = storage;
    }

    /** {@code folder}: np. {@code backgrounds}, {@code avatars}. */
    public Stored store(MultipartFile file, String folder, UUID userId) {
        byte[] content = ScanFiles.content(file);
        String contentType = ScanFiles.contentType(content);
        if (contentType == null || !contentType.startsWith("image/")) {
            throw ApiException.badRequest("Wybierz zdjęcie JPG, PNG albo WEBP");
        }
        String fileName = ScanFiles.fileName(file, contentType);
        if (!storage.enabled()) {
            return new Stored(fileName, contentType, content.length, null, content);
        }
        // Losowa część klucza: adres na publicznym CDN nie da się zgadnąć, a nowe zdjęcie ma nowy adres.
        String key = folder + "/" + userId + "/" + UUID.randomUUID() + extension(contentType);
        try {
            storage.put(key, contentType, content);
        } catch (ObjectStorage.ObjectStorageException e) {
            log.warn("Nie udało się zapisać zdjęcia {} w R2", key, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Nie udało się zapisać zdjęcia na serwerze plików. Spróbuj ponownie za chwilę");
        }
        return new Stored(fileName, contentType, content.length, key, null);
    }

    /** Adres zdjęcia: publiczny z R2 albo {@code fallbackUrl} backendu dla zdjęć zapisanych w bazie. */
    public String url(String storageKey, String fallbackUrl) {
        return storageKey == null ? fallbackUrl : storage.publicUrl(storageKey);
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }
}
