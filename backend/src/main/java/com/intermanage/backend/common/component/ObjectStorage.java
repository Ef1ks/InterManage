package com.intermanage.backend.common.component;

/**
 * Magazyn plików poza bazą (Cloudflare R2), z publicznym adresem pod domeną CDN. Pliki są tylko dopisywane: klucz
 * zawiera losową część, więc nowy plik to nowy adres, a stary zostaje (nic nie jest usuwane bezpowrotnie).
 */
public interface ObjectStorage {

    /** Czy serwer ma dane połączenia; bez nich zdjęcia zostają w bazie jak wcześniej. */
    boolean enabled();

    /** Zapisuje plik pod kluczem; błąd połączenia daje {@link ObjectStorageException}. */
    void put(String key, String contentType, byte[] content);

    /** Publiczny adres pliku (CDN). */
    String publicUrl(String key);

    class ObjectStorageException extends RuntimeException {
        public ObjectStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
