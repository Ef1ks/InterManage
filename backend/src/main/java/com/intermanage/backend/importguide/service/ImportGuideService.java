package com.intermanage.backend.importguide.service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.importguide.dto.ImportGuideRequest;
import com.intermanage.backend.importguide.dto.ImportGuideResponse;
import com.intermanage.backend.importguide.entity.ImportGuide;
import com.intermanage.backend.importguide.entity.ImportReportKind;
import com.intermanage.backend.importguide.mapper.ImportGuideMapper;
import com.intermanage.backend.importguide.repository.ImportGuideRepository;

/** Instrukcje do raportów z Importu danych, wspólne dla wszystkich sklepów. Każda zmiana zwraca całą listę. */
@Service
public class ImportGuideService {

    private final ImportGuideRepository guides;
    private final AppUserRepository users;
    private final ImportGuideMapper mapper;

    public ImportGuideService(ImportGuideRepository guides, AppUserRepository users, ImportGuideMapper mapper) {
        this.guides = guides;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ImportGuideResponse> list() {
        return guides.findByDeletedAtIsNull().stream().sorted(Comparator.comparing(ImportGuide::getKind))
                .map(mapper::toResponse).toList();
    }

    /** Zastępuje instrukcję (poprzednia dostaje znacznik); bez treści i ścieżki tylko ją usuwa. Bez zmian nic nie zapisuje. */
    @Transactional
    public List<ImportGuideResponse> save(ImportReportKind kind, ImportGuideRequest request, UUID userId) {
        String content = trimmed(request.content());
        String folderPath = trimmed(request.folderPath());
        ImportGuide current = guides.findByKindAndDeletedAtIsNull(kind).orElse(null);
        if (current == null ? content == null && folderPath == null
                : Objects.equals(trimmed(current.getContent()), content)
                        && Objects.equals(current.getFolderPath(), folderPath)) {
            return list();
        }
        AppUser by = userId != null ? users.getReferenceById(userId) : null;
        if (current != null) {
            current.delete(by);
            // Znacznik przed nowym wpisem: aktywny może być tylko jeden na raport (indeks unikalny).
            guides.flush();
        }
        if (content != null || folderPath != null) {
            guides.saveAndFlush(new ImportGuide(kind, content != null ? content : "", folderPath, by));
        }
        return list();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
