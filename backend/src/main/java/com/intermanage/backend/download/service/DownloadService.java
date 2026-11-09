package com.intermanage.backend.download.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.download.dto.DownloadChangesRequest;
import com.intermanage.backend.download.dto.DownloadRequest;
import com.intermanage.backend.download.dto.DownloadResponse;
import com.intermanage.backend.download.entity.Download;
import com.intermanage.backend.download.entity.DownloadChangeKind;
import com.intermanage.backend.download.entity.DownloadFile;
import com.intermanage.backend.download.entity.DownloadFileChange;
import com.intermanage.backend.download.mapper.DownloadMapper;
import com.intermanage.backend.download.repository.DownloadFileChangeRepository;
import com.intermanage.backend.download.repository.DownloadFileRepository;
import com.intermanage.backend.download.repository.DownloadFileSeenRepository;
import com.intermanage.backend.download.repository.DownloadRepository;

/**
 * Strona „Do pobrania”: programy i pliki dla wszystkich pracowników. Pobiera każdy zalogowany, pozycje i wersje
 * zmienia właściciel. Usunięcie pozycji to znacznik, nowa wersja pliku zastępuje poprzednią (zostaje w bazie).
 * Wersja ma listę zmian (dodano, zmieniono, naprawiono), a ogłoszona nowa wersja daje każdemu kontu baner, dopóki
 * jej nie pobierze albo nie potwierdzi, że ją ma.
 */
@Service
public class DownloadService {

    /** Tyle przyjmuje też multipart (`IMPORT_MAX_FILE_SIZE`) i nginx. */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    static final int MAX_CHANGES = 50;
    static final int MAX_CHANGE_LENGTH = 500;

    private final DownloadRepository downloads;
    private final DownloadFileRepository files;
    private final DownloadFileChangeRepository changes;
    private final DownloadFileSeenRepository seen;
    private final AppUserRepository users;
    private final DownloadMapper mapper;

    public DownloadService(DownloadRepository downloads, DownloadFileRepository files,
            DownloadFileChangeRepository changes, DownloadFileSeenRepository seen, AppUserRepository users,
            DownloadMapper mapper) {
        this.downloads = downloads;
        this.files = files;
        this.changes = changes;
        this.seen = seen;
        this.users = users;
        this.mapper = mapper;
    }

    /** Pozycje na stronie; bez właściciela tylko te z plikiem. */
    @Transactional(readOnly = true)
    public List<DownloadResponse> list(boolean owner, UUID userId) {
        Map<UUID, DownloadFileRepository.Summary> current = files.findCurrentSummaries().stream()
                .collect(Collectors.toMap(DownloadFileRepository.Summary::getDownloadId, Function.identity()));
        Map<UUID, DownloadResponse.FileInfo> infos = fileInfos(current.values());
        Set<UUID> known = userId != null ? seen.findFileIdsSeenBy(userId) : Set.of();
        return downloads.findByRemovedAtIsNullOrderByCreatedAt().stream()
                .filter(download -> owner || current.containsKey(download.getId()))
                .map(download -> {
                    DownloadFileRepository.Summary file = current.get(download.getId());
                    boolean newVersion = file != null && file.getAnnounce() && !known.contains(file.getId());
                    return mapper.toResponse(download, file != null ? infos.get(file.getId()) : null, newVersion);
                })
                .toList();
    }

    /** Wszystkie wersje pozycji z listami zmian, od najnowszej. */
    @Transactional(readOnly = true)
    public List<DownloadResponse.FileInfo> versions(UUID id) {
        List<DownloadFileRepository.Summary> list = files.findSummaries(active(id).getId());
        Map<UUID, DownloadResponse.FileInfo> infos = fileInfos(list);
        return list.stream().map(file -> infos.get(file.getId())).toList();
    }

    @Transactional
    public void create(DownloadRequest request, MultipartFile upload, String version,
            DownloadChangesRequest changeList, boolean announce, UUID userId) {
        AppUser user = user(userId);
        Download download = downloads.save(new Download(request.title().strip(), blankToNull(request.description()),
                user));
        if (upload != null && !upload.isEmpty()) {
            saveFile(download, upload, version, changeList, announce, user, userId);
        }
    }

    @Transactional
    public void update(UUID id, DownloadRequest request) {
        active(id).update(request.title().strip(), blankToNull(request.description()));
    }

    /** Nowa wersja pliku; poprzednia dostaje znacznik zastąpienia i zostaje jako historia. */
    @Transactional
    public void replaceFile(UUID id, MultipartFile upload, String version, DownloadChangesRequest changeList,
            boolean announce, UUID userId) {
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik");
        }
        saveFile(active(id), upload, version, changeList, announce, user(userId), userId);
    }

    /** Poprawia listę zmian obowiązującej wersji: poprzednie wpisy dostają znacznik, nowe ją zastępują. */
    @Transactional
    public void updateChanges(UUID id, DownloadChangesRequest changeList, UUID userId) {
        UUID fileId = currentFileId(id);
        List<Entry> entries = entries(changeList);
        AppUser user = user(userId);
        changes.findActive(List.of(fileId)).forEach(change -> change.markRemoved(user));
        saveChanges(files.getReferenceById(fileId), entries, user);
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

    /** Konto zna obowiązującą wersję: pobrało ją ({@code downloaded}) albo potwierdziło, że ją ma. */
    @Transactional
    public void markSeen(UUID id, UUID userId, boolean downloaded) {
        if (userId == null) {
            return;
        }
        seen.markSeen(currentFileId(id), userId, downloaded);
    }

    private UUID currentFileId(UUID id) {
        return files.findCurrentId(active(id).getId())
                .orElseThrow(() -> ApiException.notFound("Ta pozycja nie ma jeszcze pliku"));
    }

    private Map<UUID, DownloadResponse.FileInfo> fileInfos(Collection<DownloadFileRepository.Summary> list) {
        Map<UUID, List<DownloadFileChange>> byFile = list.isEmpty() ? Map.of()
                : changes.findActive(list.stream().map(DownloadFileRepository.Summary::getId).toList()).stream()
                        .collect(Collectors.groupingBy(DownloadFileChange::getFileId));
        return list.stream().collect(Collectors.toMap(DownloadFileRepository.Summary::getId,
                file -> mapper.toFileInfo(file, byFile.getOrDefault(file.getId(), List.of()))));
    }

    private void saveFile(Download download, MultipartFile upload, String version, DownloadChangesRequest changeList,
            boolean announce, AppUser user, UUID userId) {
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
        List<Entry> entries = entries(changeList);
        files.findCurrent(download.getId()).ifPresent(previous -> {
            previous.markReplaced(user);
            // Najpierw znacznik, potem nowa wersja: w bazie pozycja ma najwyżej jedną obowiązującą.
            files.flush();
        });
        DownloadFile file = files.save(new DownloadFile(download, fileName(upload.getOriginalFilename()), cleanVersion,
                content, sha256(content), announce, user));
        saveChanges(file, entries, user);
        if (userId != null) {
            // Kto wgrał, ten wersję zna: bez banera u siebie.
            files.flush();
            seen.markSeen(file.getId(), userId, false);
        }
    }

    private void saveChanges(DownloadFile file, List<Entry> entries, AppUser user) {
        for (int i = 0; i < entries.size(); i++) {
            changes.save(new DownloadFileChange(file, entries.get(i).kind(), entries.get(i).text(), i, user));
        }
    }

    private record Entry(DownloadChangeKind kind, String text) {
    }

    /** Wpisy listy zmian: linia = wpis, bez pustych linii i znaków wypunktowania na początku. */
    private static List<Entry> entries(DownloadChangesRequest request) {
        List<Entry> entries = new ArrayList<>();
        if (request == null) {
            return entries;
        }
        lines(request.added(), DownloadChangeKind.ADDED, entries);
        lines(request.changed(), DownloadChangeKind.CHANGED, entries);
        lines(request.fixed(), DownloadChangeKind.FIXED, entries);
        if (entries.size() > MAX_CHANGES) {
            throw ApiException.badRequest("Lista zmian może mieć najwyżej " + MAX_CHANGES + " wpisów");
        }
        return entries;
    }

    private static void lines(String text, DownloadChangeKind kind, List<Entry> entries) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\\R")) {
            String clean = line.strip().replaceFirst("^[-*•–]+\\s*", "").strip();
            if (clean.isEmpty()) {
                continue;
            }
            if (clean.length() > MAX_CHANGE_LENGTH) {
                throw ApiException.badRequest("Wpis listy zmian może mieć najwyżej " + MAX_CHANGE_LENGTH + " znaków");
            }
            entries.add(new Entry(kind, clean));
        }
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
