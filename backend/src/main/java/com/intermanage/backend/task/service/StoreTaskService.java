package com.intermanage.backend.task.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import com.intermanage.backend.push.service.PushService;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.task.dto.TaskAssigneeResponse;
import com.intermanage.backend.task.dto.TaskCounts;
import com.intermanage.backend.task.dto.TaskEventResponse;
import com.intermanage.backend.task.dto.TaskRequest;
import com.intermanage.backend.task.dto.TaskResponse;
import com.intermanage.backend.task.entity.StoreTask;
import com.intermanage.backend.task.entity.StoreTaskAttachment;
import com.intermanage.backend.task.entity.StoreTaskEvent;
import com.intermanage.backend.task.entity.StoreTaskItem;
import com.intermanage.backend.task.entity.TaskEventKind;
import com.intermanage.backend.task.entity.TaskStatus;
import com.intermanage.backend.task.mapper.TaskMapper;
import com.intermanage.backend.task.repository.StoreTaskAttachmentRepository;
import com.intermanage.backend.task.repository.StoreTaskEventRepository;
import com.intermanage.backend.task.repository.StoreTaskItemRepository;
import com.intermanage.backend.task.repository.StoreTaskRepository;

/**
 * Zadania sklepu jako lista do odhaczania. Zleca na razie tylko właściciel (hierarchia kierowników dojdzie później),
 * wykonawcą jest konto z dostępem do sklepu. Wykonawca odhacza pozycje i oznacza wykonanie (odhaczenie ostatniej
 * pozycji robi to samo), zlecający przyjmuje albo odsyła z komentarzem. Każda zmiana zapisuje wpis w historii, a
 * powiadomienie push idzie do drugiej strony.
 */
@Service
public class StoreTaskService {

    /** Jak długo przyjęte zadanie zostaje na listach. */
    private static final Duration ACCEPTED_VISIBLE = Duration.ofDays(7);
    private static final String MOBILE_URL = "/tasks";
    /** Najwięcej zrzutów i zdjęć przy jednym zadaniu. */
    public static final int MAX_ATTACHMENTS = 20;

    /** Treść załącznika do pobrania. */
    public record StoredFile(String fileName, String contentType, byte[] content) {
    }

    private final StoreTaskRepository tasks;
    private final StoreTaskItemRepository items;
    private final StoreTaskEventRepository events;
    private final StoreTaskAttachmentRepository attachments;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PushService push;
    private final TaskMapper mapper;

    public StoreTaskService(StoreTaskRepository tasks, StoreTaskItemRepository items, StoreTaskEventRepository events,
            StoreTaskAttachmentRepository attachments, StoreRepository stores, AppUserRepository users, PushService push, TaskMapper mapper) {
        this.tasks = tasks;
        this.items = items;
        this.events = events;
        this.attachments = attachments;
        this.stores = stores;
        this.users = users;
        this.push = push;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> mine(UUID storeId, UUID userId) {
        return responses(tasks.findOfAssignee(storeId, userId, acceptedSince()), userId);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> all(UUID storeId, UUID userId) {
        return responses(tasks.findOfStore(storeId, creatorFilter(userId), acceptedSince()), userId);
    }

    @Transactional(readOnly = true)
    public TaskCounts counts(UUID storeId, UUID userId) {
        long waiting = assigns(userId) ? tasks.countWaitingForAcceptance(storeId, creatorFilter(userId)) : 0;
        return new TaskCounts(tasks.countOpenOfAssignee(storeId, userId), waiting);
    }

    @Transactional(readOnly = true)
    public List<TaskAssigneeResponse> assignees(UUID storeId) {
        return tasks.findAssignees(storeId).stream().map(mapper::toAssignee).toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(UUID storeId, UUID id, UUID userId) {
        StoreTask task = visible(storeId, id, userId);
        return response(task, userId);
    }

    @Transactional(readOnly = true)
    public List<TaskEventResponse> history(UUID storeId, UUID id, UUID userId) {
        visible(storeId, id, userId);
        return events.findOfTask(id).stream().map(mapper::toEvent).toList();
    }

    @Transactional
    public TaskResponse create(UUID storeId, TaskRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie ma takiego sklepu"));
        AppUser user = users.getReferenceById(userId);
        StoreTask task = new StoreTask(store, user, trim(request.sourceKind()), trim(request.sourceLabel()),
                trim(request.sourceUrl()));
        AppUser assignee = assignee(storeId, request.assigneeId());
        task.describe(request.title().strip(), trim(request.description()), request.dueDate(), request.priority(),
                assignee);
        if (request.items() != null) {
            for (TaskRequest.Item item : request.items()) {
                task.addItem(trim(item.itm8()), trim(item.ean()), item.name().strip(), trim(item.details()));
            }
        }
        tasks.save(task);
        events.save(new StoreTaskEvent(task, TaskEventKind.CREATED, user, null));
        if (!assignee.getId().equals(userId)) {
            String body = task.getItems().isEmpty() ? task.getTitle()
                    : task.getTitle() + " (" + task.getItems().size() + " poz.)";
            push.notify(List.of(assignee.getId()), "Nowe zadanie", body, Map.of("url", MOBILE_URL));
        }
        return response(task, userId);
    }

    @Transactional
    public TaskResponse update(UUID storeId, UUID id, TaskRequest request, UUID userId) {
        StoreTask task = managed(storeId, id, userId);
        AppUser previous = task.getAssignee();
        AppUser assignee = assignee(storeId, request.assigneeId());
        task.describe(request.title().strip(), trim(request.description()), request.dueDate(), request.priority(),
                assignee);
        events.save(new StoreTaskEvent(task, TaskEventKind.EDITED, users.getReferenceById(userId), null));
        if (!previous.getId().equals(assignee.getId()) && !assignee.getId().equals(userId)) {
            push.notify(List.of(assignee.getId()), "Nowe zadanie", task.getTitle(), Map.of("url", MOBILE_URL));
        }
        return response(task, userId);
    }

    @Transactional
    public TaskResponse checkItem(UUID storeId, UUID id, UUID itemId, boolean checked, String note, UUID userId) {
        StoreTask task = active(storeId, id);
        requireAssigneeOrManager(task, userId);
        if (task.getStatus() == TaskStatus.ACCEPTED) {
            throw ApiException.conflict("Zadanie jest już przyjęte");
        }
        StoreTaskItem item = task.getItems().stream().filter(found -> found.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Nie ma takiej pozycji"));
        AppUser user = users.getReferenceById(userId);
        if (checked) {
            item.check(user, trim(note));
        } else {
            item.uncheck();
        }
        events.save(new StoreTaskEvent(task, checked ? TaskEventKind.ITEM_CHECKED : TaskEventKind.ITEM_UNCHECKED,
                user, item.getName()));
        if (checked && task.getStatus() == TaskStatus.OPEN && task.allItemsChecked()) {
            markDone(task, user, null);
        } else if (!checked && task.getStatus() == TaskStatus.DONE) {
            task.reopen();
            events.save(new StoreTaskEvent(task, TaskEventKind.UNDONE, user, null));
        }
        return response(task, userId);
    }

    @Transactional
    public TaskResponse done(UUID storeId, UUID id, String comment, UUID userId) {
        StoreTask task = active(storeId, id);
        requireAssigneeOrManager(task, userId);
        if (task.getStatus() != TaskStatus.OPEN) {
            throw ApiException.conflict("Zadanie jest już oznaczone jako wykonane");
        }
        markDone(task, users.getReferenceById(userId), trim(comment));
        return response(task, userId);
    }

    /** Wykonawca cofa oznaczenie wykonania, dopóki zlecający go nie przyjął. */
    @Transactional
    public TaskResponse undone(UUID storeId, UUID id, UUID userId) {
        StoreTask task = active(storeId, id);
        requireAssigneeOrManager(task, userId);
        if (task.getStatus() != TaskStatus.DONE) {
            throw ApiException.conflict("Zadanie nie czeka na przyjęcie");
        }
        task.reopen();
        events.save(new StoreTaskEvent(task, TaskEventKind.UNDONE, users.getReferenceById(userId), null));
        return response(task, userId);
    }

    @Transactional
    public TaskResponse accept(UUID storeId, UUID id, UUID userId) {
        StoreTask task = managed(storeId, id, userId);
        if (task.getStatus() == TaskStatus.ACCEPTED) {
            throw ApiException.conflict("Zadanie jest już przyjęte");
        }
        AppUser user = users.getReferenceById(userId);
        if (task.getStatus() == TaskStatus.OPEN) {
            task.markDone(user, null);
        }
        task.accept(user);
        events.save(new StoreTaskEvent(task, TaskEventKind.ACCEPTED, user, null));
        return response(task, userId);
    }

    /** Odesłanie do poprawy: zadanie wraca do zrobienia z komentarzem (także przyjęte, np. po pomyłce). */
    @Transactional
    public TaskResponse sendBack(UUID storeId, UUID id, String comment, UUID userId) {
        StoreTask task = managed(storeId, id, userId);
        if (task.getStatus() == TaskStatus.OPEN) {
            throw ApiException.conflict("Zadanie jest już do zrobienia");
        }
        task.sendBack(trim(comment));
        events.save(new StoreTaskEvent(task, TaskEventKind.RETURNED, users.getReferenceById(userId), trim(comment)));
        if (!task.getAssignee().getId().equals(userId)) {
            push.notify(List.of(task.getAssignee().getId()), "Zadanie do poprawy", task.getTitle(),
                    Map.of("url", MOBILE_URL));
        }
        return response(task, userId);
    }

    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        StoreTask task = managed(storeId, id, userId);
        AppUser user = users.getReferenceById(userId);
        task.markDeleted(user);
        events.save(new StoreTaskEvent(task, TaskEventKind.DELETED, user, null));
    }

    @Transactional
    public TaskResponse restore(UUID storeId, UUID id, UUID userId) {
        StoreTask task = tasks.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego zadania"));
        if (!manages(task, userId)) {
            throw ApiException.forbidden("To zadanie zlecił kto inny");
        }
        if (task.getDeletedAt() != null) {
            task.restore();
            events.save(new StoreTaskEvent(task, TaskEventKind.RESTORED, users.getReferenceById(userId), null));
        }
        return response(task, userId);
    }

    /** Zrzut ekranu albo zdjęcie (JPG, PNG, WEBP po pierwszych bajtach); dodaje wykonawca albo zlecający. */
    @Transactional
    public TaskResponse addAttachment(UUID storeId, UUID id, MultipartFile file, UUID userId) {
        StoreTask task = visible(storeId, id, userId);
        byte[] content = ScanFiles.content(file);
        String contentType = ScanFiles.contentType(content);
        if (contentType == null || !contentType.startsWith("image/")) {
            throw ApiException.badRequest("Dołącz zrzut ekranu albo zdjęcie (JPG, PNG, WEBP)");
        }
        if (attachments.countActive(task.getId()) >= MAX_ATTACHMENTS) {
            throw ApiException.conflict(
                    "Zadanie ma już " + MAX_ATTACHMENTS + " zrzutów. Usuń zbędne, żeby dodać kolejny");
        }
        AppUser user = users.getReferenceById(userId);
        StoreTaskAttachment attachment = attachments.save(
                new StoreTaskAttachment(task, fileName(file, contentType), contentType, content, user));
        events.save(new StoreTaskEvent(task, TaskEventKind.ATTACHMENT_ADDED, user, attachment.getFileName()));
        return response(task, userId);
    }

    @Transactional(readOnly = true)
    public StoredFile attachment(UUID storeId, UUID id, UUID attachmentId, UUID userId) {
        StoreTask task = visible(storeId, id, userId);
        StoreTaskAttachment attachment = attachments.findInTask(task.getId(), attachmentId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego zrzutu"));
        return new StoredFile(attachment.getFileName(), attachment.getContentType(), attachment.getContent());
    }

    @Transactional
    public TaskResponse removeAttachment(UUID storeId, UUID id, UUID attachmentId, UUID userId) {
        StoreTask task = visible(storeId, id, userId);
        StoreTaskAttachment attachment = removable(task, attachmentId, userId);
        if (attachment.getDeletedAt() == null) {
            AppUser user = users.getReferenceById(userId);
            attachment.markDeleted(user);
            events.save(new StoreTaskEvent(task, TaskEventKind.ATTACHMENT_REMOVED, user, attachment.getFileName()));
        }
        return response(task, userId);
    }

    @Transactional
    public TaskResponse restoreAttachment(UUID storeId, UUID id, UUID attachmentId, UUID userId) {
        StoreTask task = visible(storeId, id, userId);
        StoreTaskAttachment attachment = removable(task, attachmentId, userId);
        if (attachment.getDeletedAt() != null) {
            if (attachments.countActive(task.getId()) >= MAX_ATTACHMENTS) {
                throw ApiException.conflict("Zadanie ma już " + MAX_ATTACHMENTS + " zrzutów");
            }
            attachment.restore();
            events.save(new StoreTaskEvent(task, TaskEventKind.ATTACHMENT_RESTORED, users.getReferenceById(userId),
                    attachment.getFileName()));
        }
        return response(task, userId);
    }

    /** Załącznik usuwa i przywraca osoba, która go dodała, albo zlecający. */
    private StoreTaskAttachment removable(StoreTask task, UUID attachmentId, UUID userId) {
        StoreTaskAttachment attachment = attachments.findInTask(task.getId(), attachmentId)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego zrzutu"));
        AppUser author = attachment.getCreatedBy();
        if ((author == null || !author.getId().equals(userId)) && !manages(task, userId)) {
            throw ApiException.forbidden("Ten zrzut dodał kto inny");
        }
        return attachment;
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

    private void markDone(StoreTask task, AppUser user, String comment) {
        task.markDone(user, comment);
        events.save(new StoreTaskEvent(task, TaskEventKind.DONE, user, comment));
        AppUser creator = task.getCreatedBy();
        if (creator != null && !creator.getId().equals(user.getId())) {
            push.notify(List.of(creator.getId()), "Zadanie wykonane",
                    task.getAssignee().getDisplayName() + ": " + task.getTitle(), Map.of("url", MOBILE_URL));
        }
    }

    private AppUser assignee(UUID storeId, UUID assigneeId) {
        return tasks.findAssignees(storeId).stream().filter(user -> user.getId().equals(assigneeId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("To konto nie ma dostępu do tego sklepu"));
    }

    private StoreTask active(UUID storeId, UUID id) {
        StoreTask task = tasks.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego zadania"));
        if (task.getDeletedAt() != null) {
            throw ApiException.notFound("Zadanie zostało usunięte");
        }
        return task;
    }

    /** Wykonawca widzi swoje zadania, zlecający te, którymi zarządza (właściciel wszystkie). */
    private StoreTask visible(UUID storeId, UUID id, UUID userId) {
        StoreTask task = active(storeId, id);
        if (!task.getAssignee().getId().equals(userId) && !manages(task, userId)) {
            throw ApiException.notFound("Nie ma takiego zadania");
        }
        return task;
    }

    private void requireAssigneeOrManager(StoreTask task, UUID userId) {
        if (!task.getAssignee().getId().equals(userId) && !manages(task, userId)) {
            throw ApiException.forbidden("To zadanie wykonuje kto inny");
        }
    }

    private boolean isOwner(UUID userId) {
        return users.findWithRoleById(userId).map(user -> user.getRole().isOwner()).orElse(false);
    }

    private boolean assigns(UUID userId) {
        return users.findWithRoleById(userId).map(AppUser::mayAssignTasks).orElse(false);
    }

    /** Właściciel widzi wszystkie zlecone, inny zlecający tylko swoje ({@code null} = bez zawężenia). */
    private UUID creatorFilter(UUID userId) {
        return isOwner(userId) ? null : userId;
    }

    /** Zadaniem zarządza właściciel, a inny zlecający tylko tym, które sam zlecił (dopóki ma zlecanie). */
    private boolean manages(StoreTask task, UUID userId) {
        return manages(task, userId, isOwner(userId), assigns(userId));
    }

    private static boolean manages(StoreTask task, UUID userId, boolean owner, boolean assigner) {
        if (owner) {
            return true;
        }
        AppUser creator = task.getCreatedBy();
        return assigner && creator != null && creator.getId().equals(userId);
    }

    private StoreTask managed(UUID storeId, UUID id, UUID userId) {
        StoreTask task = active(storeId, id);
        if (!manages(task, userId)) {
            throw ApiException.forbidden("To zadanie zlecił kto inny");
        }
        return task;
    }

    private List<TaskResponse> responses(List<StoreTask> found, UUID userId) {
        if (found.isEmpty()) {
            return List.of();
        }
        boolean owner = isOwner(userId);
        boolean assigner = assigns(userId);
        Map<UUID, List<TaskResponse.Item>> byTask = items.findOfTasks(found.stream().map(StoreTask::getId).toList())
                .stream().collect(Collectors.groupingBy(item -> item.getTask().getId(),
                        Collectors.mapping(mapper::toItem, Collectors.toList())));
        Map<UUID, List<StoreTaskAttachmentRepository.Summary>> filesByTask = attachments
                .findOfTasks(found.stream().map(StoreTask::getId).toList()).stream()
                .collect(Collectors.groupingBy(StoreTaskAttachmentRepository.Summary::getTaskId));
        List<TaskResponse> result = new ArrayList<>(found.size());
        for (StoreTask task : found) {
            boolean manager = manages(task, userId, owner, assigner);
            List<TaskResponse.Attachment> files = filesByTask.getOrDefault(task.getId(), List.of()).stream()
                    .map(file -> toAttachment(file, manager, userId)).toList();
            result.add(mapper.toResponse(task, byTask.getOrDefault(task.getId(), List.of()), files, manager,
                    task.getAssignee().getId().equals(userId)));
        }
        return result;
    }

    private TaskResponse response(StoreTask task, UUID userId) {
        List<TaskResponse.Item> list = task.getItems().stream().map(mapper::toItem).toList();
        boolean manager = manages(task, userId);
        List<TaskResponse.Attachment> files = attachments.findOfTasks(List.of(task.getId())).stream()
                .map(file -> toAttachment(file, manager, userId)).toList();
        return mapper.toResponse(task, list, files, manager, task.getAssignee().getId().equals(userId));
    }

    private TaskResponse.Attachment toAttachment(StoreTaskAttachmentRepository.Summary file, boolean manager,
            UUID userId) {
        return mapper.toAttachment(file, manager || userId.equals(file.getCreatedById()));
    }

    private static Instant acceptedSince() {
        return Instant.now().minus(ACCEPTED_VISIBLE);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
