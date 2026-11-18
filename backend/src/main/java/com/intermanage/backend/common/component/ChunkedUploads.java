package com.intermanage.backend.common.component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import com.intermanage.backend.common.exception.ApiException;

/**
 * Plik wysyłany w kawałkach. Przy wolnym łączu sklepu (25 MB przy 0,4 Mb/s to ponad 3 min) jedno żądanie z całym
 * plikiem przekracza limit Cloudflare (odpowiedź do 100-120 s), więc przeglądarka wysyła kolejne kawałki krótkimi
 * żądaniami, a serwer dopisuje je do pliku tymczasowego. Wysyłki są w pamięci (jeden backend); niedokończone
 * znikają po {@link #EXPIRE} razem z plikiem.
 */
@Component
public class ChunkedUploads {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploads.class);

    /** Kawałek: przy 50 KB/s idzie ok. 40 s, daleko od limitu Cloudflare. */
    public static final int CHUNK_SIZE = 2 * 1024 * 1024;

    private static final Duration EXPIRE = Duration.ofHours(1);

    private final long maxSize;
    private final Map<UUID, Upload> uploads = new ConcurrentHashMap<>();

    public ChunkedUploads(@Value("${spring.servlet.multipart.max-file-size:100MB}") DataSize maxSize) {
        this.maxSize = maxSize.toBytes();
    }

    /** Odebrany plik: ścieżka na dysku (usuwa ją odbiorca) i nazwa z przeglądarki. */
    public record Received(Path file, String fileName) {
    }

    /** Zakłada wysyłkę pliku o podanym rozmiarze; `owner` (np. sklep i konto) pilnuje, że kawałki są od tej samej osoby. */
    public UUID start(String owner, String fileName, long size) {
        forgetOld();
        if (size <= 0) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        if (size > maxSize) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej " + maxSize / (1024 * 1024) + " MB).");
        }
        Path temp;
        try {
            temp = Files.createTempFile("chunked-upload-", ".part");
        } catch (IOException e) {
            log.warn("Nie udało się założyć pliku wysyłki", e);
            throw ApiException.badRequest("Nie udało się przyjąć pliku. Spróbuj ponownie.");
        }
        UUID id = UUID.randomUUID();
        uploads.put(id, new Upload(owner, fileName, size, temp));
        return id;
    }

    /**
     * Dopisuje kawałek o numerze `index` (od 0). Kawałki idą po kolei; powtórzony (przeglądarka nie dostała odpowiedzi
     * i wysłała go ponownie) jest pomijany.
     */
    public void append(String owner, UUID id, int index, byte[] bytes) {
        Upload upload = find(owner, id);
        synchronized (upload) {
            if (index < upload.chunks) {
                return;
            }
            if (index != upload.chunks) {
                throw ApiException.conflict("Brakuje części pliku. Wyślij plik ponownie.");
            }
            if (bytes.length == 0 || bytes.length > CHUNK_SIZE || upload.received + bytes.length > upload.size) {
                throw ApiException.badRequest("Nieprawidłowa część pliku. Wyślij plik ponownie.");
            }
            try (OutputStream out = Files.newOutputStream(upload.file, StandardOpenOption.APPEND)) {
                out.write(bytes);
            } catch (IOException e) {
                log.warn("Nie udało się zapisać części pliku {}", upload.fileName, e);
                throw ApiException.badRequest("Nie udało się zapisać części pliku. Spróbuj ponownie.");
            }
            upload.received += bytes.length;
            upload.chunks++;
            upload.touchedAt = Instant.now();
        }
    }

    /** Kończy wysyłkę: plik musi mieć cały zapowiedziany rozmiar. Od tej chwili plik należy do wywołującego. */
    public Received finish(String owner, UUID id) {
        Upload upload = find(owner, id);
        synchronized (upload) {
            if (upload.received != upload.size) {
                throw ApiException.conflict("Plik nie dotarł w całości. Wyślij go ponownie.");
            }
            uploads.remove(id);
            return new Received(upload.file, upload.fileName);
        }
    }

    private Upload find(String owner, UUID id) {
        Upload upload = uploads.get(id);
        if (upload == null || !upload.owner.equals(owner)) {
            throw ApiException.notFound("Nie znaleziono wysyłanego pliku. Wyślij go ponownie.");
        }
        return upload;
    }

    private void forgetOld() {
        Instant limit = Instant.now().minus(EXPIRE);
        uploads.entrySet().removeIf(entry -> {
            if (entry.getValue().touchedAt.isAfter(limit)) {
                return false;
            }
            try {
                Files.deleteIfExists(entry.getValue().file);
            } catch (IOException e) {
                log.warn("Nie udało się usunąć pliku porzuconej wysyłki {}", entry.getValue().file, e);
            }
            return true;
        });
    }

    private static final class Upload {

        private final String owner;
        private final String fileName;
        private final long size;
        private final Path file;
        private long received;
        private int chunks;
        private volatile Instant touchedAt = Instant.now();

        Upload(String owner, String fileName, long size, Path file) {
            this.owner = owner;
            this.fileName = fileName;
            this.size = size;
            this.file = file;
        }
    }
}
