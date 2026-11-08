package com.intermanage.backend.download.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.download.dto.DownloadRequest;
import com.intermanage.backend.download.dto.DownloadResponse;
import com.intermanage.backend.download.entity.Download;
import com.intermanage.backend.download.entity.DownloadFile;
import com.intermanage.backend.download.mapper.DownloadMapper;
import com.intermanage.backend.download.repository.DownloadFileRepository;
import com.intermanage.backend.download.repository.DownloadRepository;

/**
 * Strona „Do pobrania”: programy i pliki dla wszystkich pracowników. Pobiera każdy zalogowany, pozycje i wersje
 * zmienia właściciel. Usunięcie pozycji to znacznik, nowa wersja pliku zastępuje poprzednią (zostaje w bazie).
 */
@Service
public class DownloadService {

    /** Tyle przyjmuje też multipart (`IMPORT_MAX_FILE_SIZE`) i nginx. */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final DownloadRepository downloads;
    private final DownloadFileRepository files;
    private final AppUserRepository users;
    private final DownloadMapper mapper;

    public DownloadService(DownloadRepository downloads, DownloadFileRepository files, AppUserRepository users,
            DownloadMapper mapper) {
        this.downloads = downloads;
        this.files = files;
        this.users = users;
        this.mapper = mapper;
    }

    /** Pozycje na stronie; bez właściciela tylko te z plikiem. */
    @Transactional(readOnly = true)
    public List<DownloadResponse> list(boolean owner) {
        Map<UUID, DownloadFileRepository.Summary> current = files.findCurrentSummaries().stream()
                .collect(Collectors.toMap(DownloadFileRepository.Summary::getDownloadId, Function.identity()));
        return downloads.findByRemovedAtIsNullOrderByCreatedAt().stream()
                .filter(download -> owner || current.containsKey(download.getId()))
                .map(download -> mapper.toResponse(download, current.get(download.getId())))
                .toList();
    }

    @Transactional
    public void create(DownloadRequest request, MultipartFile upload, String version, UUID userId) {
        AppUser user = user(userId);
        Download download = downloads.save(new Download(request.title().strip(), blankToNull(request.description()),
                user));
        if (upload != null && !upload.isEmpty()) {
            saveFile(download, upload, version, user);
        }
    }

    @Transactional
    public void update(UUID id, DownloadRequest request) {
        active(id).update(request.title().strip(), blankToNull(request.description()));
    }

    /** Nowa wersja pliku; poprzednia dostaje znacznik zastąpienia i zostaje jako historia. */
    @Transactional
    public void replaceFile(UUID id, MultipartFile upload, String version, UUID userId) {
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik");
        }
        saveFile(active(id), upload, version, user(userId));
    }

    /** Usuwa pozycję (znacznik); „Przywróć” to {@link #restore}. */
    @Transactional
    public void remove(UUID id, UUID userId) {
        active(id).markRemoved(user(userId));
    }

    @Transactional
    public void restore(UUID id) {
        downloads.findById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono pozycji")).restore();
    }

    /** Obowiązująca wersja pliku do pobrania. */
    @Transactional(readOnly = true)
    public DownloadFile file(UUID id) {
        return files.findCurrent(active(id).getId())
                .orElseThrow(() -> ApiException.notFound("Ta pozycja nie ma jeszcze pliku"));
    }

    private void saveFile(Download download, MultipartFile upload, String version, AppUser user) {
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej " + MAX_FILE_BYTES / 1024 / 1024 + " MB)");
        }
        byte[] content;
        try {
            content = upload.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
        String cleanVersion = blankToNull(version);
        if (cleanVersion != null && cleanVersion.length() > 40) {
            throw ApiException.badRequest("Wersja może mieć najwyżej 40 znaków");
        }
        files.findCurrent(download.getId()).ifPresent(previous -> {
            previous.markReplaced(user);
            // Najpierw znacznik, potem nowa wersja: w bazie pozycja ma najwyżej jedną obowiązującą.
            files.flush();
        });
        files.save(new DownloadFile(download, fileName(upload.getOriginalFilename()), cleanVersion, content,
                sha256(content), user));
    }

    private Download active(UUID id) {
        return downloads.findById(id).filter(download -> download.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pozycji"));
    }

    private static String fileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            name = "plik";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
