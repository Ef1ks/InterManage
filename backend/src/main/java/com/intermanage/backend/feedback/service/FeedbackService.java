package com.intermanage.backend.feedback.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.ScanFiles;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.feedback.dto.FeedbackCounts;
import com.intermanage.backend.feedback.dto.FeedbackResponse;
import com.intermanage.backend.feedback.entity.FeedbackAttachment;
import com.intermanage.backend.feedback.entity.FeedbackKind;
import com.intermanage.backend.feedback.entity.FeedbackReport;
import com.intermanage.backend.feedback.entity.FeedbackStatus;
import com.intermanage.backend.feedback.mapper.FeedbackMapper;
import com.intermanage.backend.feedback.repository.FeedbackAttachmentRepository;
import com.intermanage.backend.feedback.repository.FeedbackReportRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Zgłoszenia poprawek i błędów: zgłasza każdy zalogowany (z bieżącej strony, ze zrzutami), czyta i prowadzi właściciel.
 */
@Service
public class FeedbackService {

    /** Najwięcej zrzutów przy jednym zgłoszeniu (tak samo we froncie). */
    public static final int MAX_ATTACHMENTS = 10;
    private static final int MAX_DESCRIPTION = 5000;

    public record StoredFile(String fileName, String contentType, byte[] content) {
    }

    private final FeedbackReportRepository reports;
    private final FeedbackAttachmentRepository attachments;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final FeedbackMapper mapper;

    public FeedbackService(FeedbackReportRepository reports, FeedbackAttachmentRepository attachments,
            StoreRepository stores, AppUserRepository users, FeedbackMapper mapper) {
        this.reports = reports;
        this.attachments = attachments;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * Nowe zgłoszenie. {@code storeId} = sklep, w którym pracował zgłaszający (sprawdzony dostęp w kontrolerze, inaczej
     * null). Wszystkie zrzuty sprawdzane przed zapisem, więc zły plik nie zostawia zgłoszenia bez części zrzutów.
     */
    @Transactional
    public FeedbackResponse create(UUID storeId, FeedbackKind kind, String description, String pageUrl,
            String pageLabel, String userAgent, List<MultipartFile> files, UUID userId) {
        String text = description == null ? "" : description.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("Opisz, co poprawić");
        }
        if (text.length() > MAX_DESCRIPTION) {
            throw ApiException.badRequest("Opis może mieć najwyżej " + MAX_DESCRIPTION + " znaków");
        }
        List<MultipartFile> given = files == null ? List.of()
                : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (given.size() > MAX_ATTACHMENTS) {
            throw ApiException.badRequest("Najwyżej " + MAX_ATTACHMENTS + " zrzutów przy jednym zgłoszeniu");
        }
        record Shot(String name, String type, byte[] content) {
        }
        List<Shot> shots = new ArrayList<>();
        for (MultipartFile file : given) {
            byte[] content = ScanFiles.content(file);
            String type = ScanFiles.contentType(content);
            if (type == null || !type.startsWith("image/")) {
                throw ApiException.badRequest("Dołącz zrzut ekranu albo zdjęcie (JPG, PNG, WEBP)");
            }
            shots.add(new Shot(fileName(file, type), type, content));
        }
        AppUser user = users.getReferenceById(userId);
        FeedbackReport report = reports.save(new FeedbackReport(
                storeId == null ? null : stores.getReferenceById(storeId), kind == null ? FeedbackKind.BUG : kind,
                text, limit(pageUrl, 2000), limit(pageLabel, 200), limit(userAgent, 500), user));
        for (Shot shot : shots) {
            attachments.save(new FeedbackAttachment(report, shot.name(), shot.type(), shot.content(), user));
        }
        return responses(List.of(report)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> list(FeedbackStatus status) {
        return responses(reports.findActive(status));
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> mine(UUID userId) {
        return responses(reports.findOfUser(userId));
    }

    @Transactional(readOnly = true)
    public FeedbackCounts counts() {
        Map<FeedbackStatus, Long> counts = new EnumMap<>(FeedbackStatus.class);
        reports.countByStatus().forEach(row -> counts.put(row.getStatus(), row.getCount()));
        return new FeedbackCounts(counts.getOrDefault(FeedbackStatus.NEW, 0L),
                counts.getOrDefault(FeedbackStatus.IN_PROGRESS, 0L), counts.getOrDefault(FeedbackStatus.DONE, 0L),
                counts.getOrDefault(FeedbackStatus.REJECTED, 0L));
    }

    @Transactional
    public FeedbackResponse changeStatus(UUID id, FeedbackStatus status, String note, UUID userId) {
        FeedbackReport report = active(id);
        String text = note == null || note.isBlank() ? null : note.strip();
        report.changeStatus(status, text, users.getReferenceById(userId));
        return responses(List.of(report)).getFirst();
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        FeedbackReport report = reports.findById(id).orElseThrow(FeedbackService::missing);
        if (report.getDeletedAt() == null) {
            report.markDeleted(users.getReferenceById(userId));
        }
    }

    @Transactional
    public FeedbackResponse restore(UUID id) {
        FeedbackReport report = reports.findById(id).orElseThrow(FeedbackService::missing);
        report.restore();
        return responses(List.of(report)).getFirst();
    }

    @Transactional(readOnly = true)
    public StoredFile attachment(UUID id, UUID attachmentId) {
        FeedbackReport report = reports.findById(id).orElseThrow(FeedbackService::missing);
        FeedbackAttachment attachment = attachments.findInReport(report.getId(), attachmentId)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego zrzutu"));
        return new StoredFile(attachment.getFileName(), attachment.getContentType(), attachment.getContent());
    }

    private FeedbackReport active(UUID id) {
        return reports.findById(id).filter(report -> report.getDeletedAt() == null)
                .orElseThrow(FeedbackService::missing);
    }

    private static ApiException missing() {
        return ApiException.notFound("Nie ma takiego zgłoszenia");
    }

    private List<FeedbackResponse> responses(List<FeedbackReport> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<FeedbackAttachmentRepository.Summary>> files = attachments
                .findOfReports(list.stream().map(FeedbackReport::getId).toList()).stream()
                .collect(Collectors.groupingBy(FeedbackAttachmentRepository.Summary::getReportId));
        return list.stream()
                .map(report -> mapper.toResponse(report, files.getOrDefault(report.getId(), List.of()).stream()
                        .map(mapper::toAttachment).toList()))
                .toList();
    }

    private static String limit(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String fileName(MultipartFile file, String contentType) {
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.equals("blob")) {
            name = "zrzut-ekranu" + extension;
        }
        return name.length() > 200 ? name.substring(0, 200 - extension.length()) + extension : name;
    }
}
