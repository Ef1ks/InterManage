package com.intermanage.backend.store.service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
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
 */
@Service
public class StoreFileQueueService {

    private static final Pattern FOLDER_KEY = Pattern.compile("[A-Za-z0-9:_-]{1,100}");
    private static final Pattern BAD_FILE_NAME = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    private final StoreFileQueueRepository queue;
    private final AppUserRepository users;
    private final StoreMapper mapper;

    public StoreFileQueueService(StoreFileQueueRepository queue, AppUserRepository users, StoreMapper mapper) {
        this.queue = queue;
        this.users = users;
        this.mapper = mapper;
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
    public FileQueueItemResponse add(UUID storeId, String folderKey, String folderLabel, String fileName,
            MultipartFile file, UUID userId) {
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
        StoreFileQueueItem saved = queue.save(
                new StoreFileQueueItem(storeId, folderKey, label, name, content, user(userId)));
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

    @Transactional
    public void markSaved(UUID storeId, UUID id, UUID userId) {
        StoreFileQueueItem item = file(storeId, id);
        item.markSaved(user(userId));
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID userId) {
        StoreFileQueueItem item = find(storeId, id);
        if (item.getSavedAt() != null) {
            throw ApiException.conflict("Ten plik jest już zapisany w folderze sklepu");
        }
        if (item.getRemovedAt() == null) {
            item.markRemoved(user(userId));
        }
    }

    @Transactional
    public void restore(UUID storeId, UUID id) {
        find(storeId, id).restore();
    }

    private StoreFileQueueItem find(UUID storeId, UUID id) {
        return queue.findById(id).filter(item -> item.getStoreId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku w kolejce"));
    }

    private AppUser user(UUID userId) {
        return users.getReferenceById(userId);
    }
}
