package com.intermanage.backend.store.service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.dto.FileQueueItemResponse;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.mapper.StoreMapper;
import com.intermanage.backend.store.repository.StoreFileQueueRepository;

/**
 * Kolejka plików sklepu. Kto pracuje dla innego sklepu bez połączenia z jego komputerem, włącza w przeglądarce
 * kolejkę dla tego sklepu: pliki, które poszłyby do folderu (protokoły, zmiana cen, XML faktury, nowe artykuły…),
 * czekają tutaj, a pod koniec pracy na komputerze sklepu „Zapisz wszystkie” zapisuje je w folderach w kolejności
 * dodania i potwierdza każdy ({@link #markSaved}). Pliki o tej samej nazwie zapisują się po kolei, więc wygrywa
 * nowszy, jak przy zapisie od razu.
 *
 * <p>Z tej samej kolejki zapisuje agent sklepu (moduł {@code agent}): czeka na pliki stałym połączeniem i dostaje je
 * od razu po dodaniu ({@link FileQueued}). Przed zapisem plik trzeba sobie przydzielić (agent albo przeglądarka jako
 * zapas, {@link #claim}), a {@code saved} i {@code failed} przyjmujemy tylko od posiadacza przydziału, więc plik nie
 * zapisze się dwa razy. Każda zmiana idzie do przeglądarek na żywo (temat {@code file-queue}).
 */
@Service
public class StoreFileQueueService {

    private static final Pattern FOLDER_KEY = Pattern.compile("[A-Za-z0-9:_-]{1,100}");
    private static final Pattern BAD_FILE_NAME = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    private final StoreFileQueueRepository queue;
    private final AppUserRepository users;
    private final StoreMapper mapper;
    private final LiveEvents live;
    private final ApplicationEventPublisher events;

    /** Plik dodany do kolejki sklepu (po zatwierdzeniu budzi agenta, który czeka na pliki). */
    public record FileQueued(UUID storeId, UUID id) {
    }

    /** Agent zapisał plik w folderze sklepu i to potwierdził. */
    public record FileSavedByAgent(UUID storeId, UUID id, String folderKey) {
    }

    /**
     * Stan pliku: {@code state} = WAITING (czeka), CLAIMED (ktoś go zapisuje), SAVED, FAILED (ostatni zapis się nie
     * udał), REMOVED.
     */
    public record FileStatus(UUID id, String fileName, String folderLabel, String state, Instant savedAt,
            boolean savedByAgent, String claimedBy, String error) {
    }

    public StoreFileQueueService(StoreFileQueueRepository queue, AppUserRepository users, StoreMapper mapper,
            LiveEvents live, ApplicationEventPublisher events) {
        this.queue = queue;
        this.users = users;
        this.mapper = mapper;
        this.live = live;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<FileQueueItemResponse> pending(UUID storeId) {
        return queue.findPending(storeId).stream().map(mapper::toQueueItem).toList();
    }

    @Transactional(readOnly = true)
    public long countPending(UUID storeId) {
        return queue.countPending(storeId);
    }

    @Transactional
    public FileQueueItemResponse add(UUID storeId, String folderKey, String folderLabel, String groupKey,
            String groupLabel, String fileName, MultipartFile file, UUID userId) {
        if (folderKey == null || !FOLDER_KEY.matcher(folderKey).matches()) {
            throw ApiException.badRequest("Nieznany folder pliku");
        }
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty() || name.length() > 255 || BAD_FILE_NAME.matcher(name).find()) {
            throw ApiException.badRequest("Nieprawidłowa nazwa pliku");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Plik jest pusty");
        }
        String label = folderLabel == null || folderLabel.isBlank() ? folderKey : folderLabel.trim();
        if (label.length() > 200) {
            label = label.substring(0, 200);
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
        return add(storeId, folderKey, label, groupKey, groupLabel, name, content, userId);
    }

    /**
     * Plik złożony przez serwer (np. cenówki po makrze promocji z kolektora); {@code userId} pusty = bez osoby. Agent
     * sklepu zapisze go jak plik z przeglądarki.
     */
    @Transactional
    public FileQueueItemResponse add(UUID storeId, String folderKey, String label, String groupKey,
            String groupLabel, String name, byte[] content, UUID userId) {
        String group = trimmed(groupKey, 200);
        String groupName = group == null ? null : trimmed(groupLabel, 300);
        StoreFileQueueItem saved = queue.saveAndFlush(new StoreFileQueueItem(storeId, folderKey, label, group,
                groupName == null ? group : groupName, name, content, userId == null ? null : user(userId)));
        publish(storeId, "added", saved.getId(), name, label, null, Map.of());
        events.publishEvent(new FileQueued(storeId, saved.getId()));
        return pending(storeId).stream().filter(item -> item.id().equals(saved.getId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku w kolejce"));
    }

    @Transactional(readOnly = true)
    public StoreFileQueueItem file(UUID storeId, UUID id) {
        StoreFileQueueItem item = find(storeId, id);
        if (item.getRemovedAt() != null) {
            throw ApiException.notFound("Ten plik usunięto z kolejki");
        }
        return item;
    }

    /**
     * Przydział pliku osobie przed zapisem z przeglądarki (zapas, gdy agent nie odpowiada, albo „Zapisz wszystkie”).
     * 409, gdy plik zapisuje właśnie ktoś inny (agent) albo jest już zapisany.
     */
    @Transactional
    public FileStatus claim(UUID storeId, UUID id, UUID userId) {
        StoreFileQueueItem item = file(storeId, id);
        if (queue.claimForUser(storeId, id, userId) == 0) {
            FileStatus status = status(storeId, id);
            throw ApiException.conflict("SAVED".equals(status.state())
                    ? "Ten plik jest już zapisany w folderze sklepu"
                    : "Ten plik zapisuje teraz " + (status.claimedBy() == null ? "ktoś inny" : status.claimedBy())
                            + ". Poczekaj chwilę.");
        }
        publish(storeId, "claimed", id, item.getFileName(), null, null, Map.of("by", "user"));
        return status(storeId, id);
    }

    /** Plik zapisany z przeglądarki: tylko przez osobę, która go sobie przydzieliła ({@link #claim}). */
    @Transactional
    public void markSaved(UUID storeId, UUID id, UUID userId) {
        StoreFileQueueItem item = file(storeId, id);
        if (queue.savedByUser(storeId, id, userId) == 0) {
            throw ApiException.conflict(item.getSavedAt() != null
                    ? "Ten plik jest już zapisany w folderze sklepu"
                    : "Ten plik nie jest przydzielony do zapisu z tej przeglądarki. Spróbuj ponownie.");
        }
        publish(storeId, "saved", id, item.getFileName(), null, null, Map.of("by", "user"));
    }

    /** Nieudany zapis z przeglądarki: zdejmuje przydział i zapamiętuje opis. */
    @Transactional
    public void markFailed(UUID storeId, UUID id, UUID userId, String error) {
        StoreFileQueueItem item = file(storeId, id);
        if (queue.failedByUser(storeId, id, userId, errorText(error)) == 0) {
            throw ApiException.conflict("Ten plik nie jest przydzielony do zapisu z tej przeglądarki");
        }
        publish(storeId, "failed", id, item.getFileName(), null, errorText(error), Map.of("by", "user"));
    }

    @Transactional(readOnly = true)
    public FileStatus status(UUID storeId, UUID id) {
        StoreFileQueueRepository.Status found = queue.findStatus(storeId, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku w kolejce"));
        String state = found.getSavedAt() != null ? "SAVED"
                : found.getRemovedAt() != null ? "REMOVED"
                : found.getClaimedBy() != null ? "CLAIMED"
                : found.getFailedAt() != null ? "FAILED" : "WAITING";
        return new FileStatus(found.getId(), found.getFileName(), found.getFolderLabel(), state, found.getSavedAt(),
                found.getSavedByAgent(), found.getClaimedBy(), found.getLastError());
    }

    /** Agent sklepu: przydziela sobie czekające pliki z folderów, które zna (najwyżej {@code limit}). */
    @Transactional
    public List<StoreFileQueueItem> claimForAgent(UUID storeId, UUID agentId, List<String> folderKeys, int limit) {
        if (folderKeys.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = queue.claimForAgent(storeId, agentId, folderKeys, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<StoreFileQueueItem> items = queue.findOrdered(ids);
        items.forEach(item -> publish(storeId, "claimed", item.getId(), item.getFileName(), null, null,
                Map.of("by", "agent")));
        return items;
    }

    /** Agent oddaje przydział bez błędu (np. odpowiedź do niego nie doszła). */
    @Transactional
    public void releaseForAgent(UUID agentId, List<UUID> ids) {
        if (!ids.isEmpty()) {
            queue.release(ids, agentId);
        }
    }

    @Transactional
    public void savedByAgent(UUID storeId, UUID id, UUID agentId) {
        StoreFileQueueItem item = find(storeId, id);
        if (queue.savedByAgent(storeId, id, agentId) == 0) {
            throw ApiException.conflict("Ten plik nie jest przydzielony temu agentowi");
        }
        publish(storeId, "saved", id, item.getFileName(), null, null, Map.of("by", "agent"));
        events.publishEvent(new FileSavedByAgent(storeId, id, item.getFolderKey()));
    }

    @Transactional
    public void failedByAgent(UUID storeId, UUID id, UUID agentId, String error) {
        StoreFileQueueItem item = find(storeId, id);
        if (queue.failedByAgent(storeId, id, agentId, errorText(error)) == 0) {
            throw ApiException.conflict("Ten plik nie jest przydzielony temu agentowi");
        }
        publish(storeId, "failed", id, item.getFileName(), null, errorText(error), Map.of("by", "agent"));
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID userId) {
        StoreFileQueueItem item = find(storeId, id);
        if (item.getSavedAt() != null) {
            throw ApiException.conflict("Ten plik jest już zapisany w folderze sklepu");
        }
        if (item.getRemovedAt() == null) {
            item.markRemoved(user(userId));
            publish(storeId, "removed", id, item.getFileName(), null, null, Map.of());
        }
    }

    @Transactional
    public void restore(UUID storeId, UUID id) {
        StoreFileQueueItem item = find(storeId, id);
        item.restore();
        publish(storeId, "added", id, item.getFileName(), null, null, Map.of());
        events.publishEvent(new FileQueued(storeId, id));
    }

    private StoreFileQueueItem find(UUID storeId, UUID id) {
        return queue.findById(id).filter(item -> item.getStoreId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku w kolejce"));
    }

    private void publish(UUID storeId, String action, UUID id, String fileName, String folderLabel, String error,
            Map<String, String> extra) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", action);
        fields.put("id", id);
        fields.put("fileName", fileName);
        if (folderLabel != null) {
            fields.put("folderLabel", folderLabel);
        }
        if (error != null) {
            fields.put("error", error);
        }
        fields.put("at", Instant.now());
        fields.putAll(extra);
        live.publish(storeId, "file-queue", fields);
    }

    private static String errorText(String error) {
        String text = error == null || error.isBlank() ? "Nie udało się zapisać pliku" : error.strip();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static String trimmed(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private AppUser user(UUID userId) {
        return users.getReferenceById(userId);
    }
}
