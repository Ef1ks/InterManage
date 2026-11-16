package com.intermanage.backend.download.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.download.component.WindowsShortcut;
import com.intermanage.backend.download.dto.ShortcutResponse;
import com.intermanage.backend.download.entity.DownloadShortcut;
import com.intermanage.backend.download.mapper.DownloadMapper;
import com.intermanage.backend.download.repository.DownloadShortcutRepository;

/**
 * Skróty do folderów na stronie „Do pobrania”: właściciel wgrywa skrót Windows i nadaje mu nazwę, każdy zalogowany
 * pobiera go jako {@code <nazwa>.lnk} albo wszystkie naraz w ZIP-ie. Usunięcie to znacznik.
 */
@Service
public class DownloadShortcutService {

    /** Skrót ma zwykle 1-2 kB. */
    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_NAME_LENGTH = 100;

    private static final UUID NONE = new UUID(0, 0);

    private final DownloadShortcutRepository shortcuts;
    private final AppUserRepository users;
    private final DownloadMapper mapper;

    public DownloadShortcutService(DownloadShortcutRepository shortcuts, AppUserRepository users,
            DownloadMapper mapper) {
        this.shortcuts = shortcuts;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ShortcutResponse> list() {
        return shortcuts.findActiveSummaries().stream().map(mapper::toShortcut).toList();
    }

    @Transactional
    public void create(String name, MultipartFile upload, UUID userId) {
        String clean = name(name, NONE);
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("Wybierz skrót");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("To za duży plik jak na skrót");
        }
        byte[] content;
        try {
            content = upload.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
        if (!WindowsShortcut.isShortcut(content)) {
            throw ApiException.badRequest("To nie jest skrót Windows (plik .lnk)");
        }
        String target = WindowsShortcut.target(content).map(path -> path.length() > 1000 ? null : path).orElse(null);
        shortcuts.save(new DownloadShortcut(clean, target, content, user(userId)));
    }

    @Transactional
    public void rename(UUID id, String name) {
        DownloadShortcut shortcut = active(id);
        shortcut.rename(name(name, shortcut.getId()));
    }

    @Transactional
    public void remove(UUID id, UUID userId) {
        active(id).markRemoved(user(userId));
    }

    @Transactional
    public void restore(UUID id) {
        DownloadShortcut shortcut = shortcuts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono skrótu"));
        if (shortcut.getRemovedAt() == null) {
            return;
        }
        if (shortcuts.existsActiveName(shortcut.getName(), shortcut.getId())) {
            throw ApiException.conflict("Jest już skrót o nazwie „" + shortcut.getName() + "”");
        }
        shortcut.restore();
    }

    @Transactional(readOnly = true)
    public DownloadShortcut file(UUID id) {
        return active(id);
    }

    /** Wszystkie skróty w jednym ZIP-ie, każdy jako {@code <nazwa>.lnk}. */
    @Transactional(readOnly = true)
    public byte[] zip() {
        List<DownloadShortcut> list = shortcuts.findActive();
        if (list.isEmpty()) {
            throw ApiException.notFound("Nie ma jeszcze skrótów");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (DownloadShortcut shortcut : list) {
                zip.putNextEntry(new ZipEntry(shortcut.getFileName()));
                zip.write(shortcut.getContent());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Nazwa pliku Windows: bez znaków niedozwolonych, kropki i spacji na końcu; końcówkę .lnk dokłada pobranie. */
    private String name(String name, UUID exceptId) {
        String clean = name == null ? "" : name.strip();
        if (clean.toLowerCase().endsWith(".lnk")) {
            clean = clean.substring(0, clean.length() - 4).strip();
        }
        if (clean.isEmpty()) {
            throw ApiException.badRequest("Podaj nazwę skrótu");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw ApiException.badRequest("Nazwa może mieć najwyżej " + MAX_NAME_LENGTH + " znaków");
        }
        if (clean.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) {
            throw ApiException.badRequest("Nazwa nie może mieć znaków \\ / : * ? \" < > |");
        }
        if (clean.endsWith(".")) {
            throw ApiException.badRequest("Nazwa nie może kończyć się kropką");
        }
        if (shortcuts.existsActiveName(clean, exceptId)) {
            throw ApiException.conflict("Jest już skrót o nazwie „" + clean + "”");
        }
        return clean;
    }

    private DownloadShortcut active(UUID id) {
        return shortcuts.findById(id).filter(shortcut -> shortcut.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono skrótu"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
