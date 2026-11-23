package com.intermanage.backend.task.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.service.StoreRoleService;
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
 * Zadania sklepu jako lista do odhaczania. Zleca właściciel, Aderon, konto z włączonym zlecaniem i każda ranga z kimś
 * pod sobą w hierarchii sklepu (osobom niżej), wykonawcą jest konto z dostępem do sklepu. Wykonawca odhacza pozycje i oznacza wykonanie (odhaczenie ostatniej
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
    private final StoreRoleService ranks;

    public StoreTaskService(StoreTaskRepository tasks, StoreTaskItemRepository items, StoreTaskEventRepository events,
            StoreTaskAttachmentRepository attachments, StoreRepository stores, AppUserRepository users, PushService push,
            TaskMapper mapper, StoreRoleService ranks) {
        this.tasks = tasks;
        this.items = items;
        this.events = events;
        this.attachments = attachments;
        this.stores = stores;
        this.users = users;
        this.push = push;
        this.mapper = mapper;
        this.ranks = ranks;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> mine(UUID storeId, UUID userId) {
        return responses(tasks.findOfAssignee(storeId, userId, acceptedSince()), userId);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> all(UUID storeId, UUID userId) {
        return responses(tasks.findOfStore(storeId, creatorFilter(userId, storeId), acceptedSince()), userId);
    }

    @Transactional(readOnly = true)
    public TaskCounts counts(UUID storeId, UUID userId) {
        long waiting = assigns(userId, storeId)
                ? tasks.countWaitingForAcceptance(storeId, creatorFilter(userId, storeId)) : 0;
        return new TaskCounts(tasks.countOpenOfAssignee(storeId, userId), waiting, tasks.countOpenForStore(storeId));
    }

    @Transactional(readOnly = true)
    public List<TaskAssigneeResponse> assignees(UUID storeId, UUID userId) {
        return assignable(storeId, userId).stream().map(user -> mapper.toAssignee(user, storeId)).toList();
    }

    @Transactional(readOnly = true)
    public boolean canAssignToStore(UUID storeId, UUID userId) {
        return assignsToStore(userId, storeId);
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

    /**
     * Nowe zadanie: jednej osobie, kilku osobom (każda dostaje swoją kopię, kopie łączy grupa) albo całemu sklepowi
     * (bez wykonawcy, wykonuje ktokolwiek ze sklepu). Odpowiedź to pierwsza kopia.
     */
    @Transactional
    public TaskResponse create(UUID storeId, TaskRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie ma takiego sklepu"));
        AppUser user = users.getReferenceById(userId);
        List<AppUser> targets = targets(storeId, request, userId, true);
        UUID groupId = targets.size() > 1 ? UUID.randomUUID() : null;
        StoreTask first = null;
        for (AppUser assignee : targets) {
            StoreTask task = new StoreTask(store, user, trim(request.sourceKind()), trim(request.sourceLabel()),
                    trim(request.sourceUrl()), groupId);
            task.describe(request.title().strip(), trim(request.description()), request.dueDate(),
                    request.priority(), assignee);
            if (request.items() != null) {
                for (TaskRequest.Item item : request.items()) {
                    task.addItem(trim(item.itm8()), trim(item.ean()), item.name().strip(), trim(item.details()));
                }
            }
            tasks.save(task);
            events.save(new StoreTaskEvent(task, TaskEventKind.CREATED, user, null));
            // Zadanie całego sklepu nie wysyła powiadomień do wszystkich: widać je na liście i na czacie.
            if (assignee != null && !assignee.getId().equals(userId)) {
                String body = task.getItems().isEmpty() ? task.getTitle()
                        : task.getTitle() + " (" + task.getItems().size() + " poz.)";
                push.notify(List.of(assignee.getId()), "Nowe zadanie", body, Map.of("url", MOBILE_URL));
            }
            if (first == null) {
                first = task;
            }
        }
        return response(first, userId);
    }

    /**
     * Wykonawcy z żądania: {@code forStore} = jeden wpis {@code null} (cały sklep), inaczej osoby z
     * {@code assigneeIds} i {@code assigneeId}. Przy zmianie zadania ({@code many} = false) najwyżej jedna osoba.
     */
    private List<AppUser> targets(UUID storeId, TaskRequest request, UUID userId, boolean many) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (request.assigneeIds() != null) {
            ids.addAll(request.assigneeIds());
        }
        if (request.assigneeId() != null) {
            ids.add(request.assigneeId());
        }
        if (Boolean.TRUE.equals(request.forStore())) {
            if (!ids.isEmpty()) {
                throw ApiException.badRequest("Zadanie zlecisz albo całemu sklepowi, albo wybranym osobom");
            }
            if (!assignsToStore(userId, storeId)) {
                throw ApiException.forbidden(
                        "Całemu sklepowi zleca właściciel, Aderon i konto z włączonym zlecaniem zadań");
            }
            return Collections.singletonList(null);
        }
        if (ids.isEmpty()) {
            throw ApiException.badRequest("Wybierz, kto ma to zrobić");
        }
        if (!many && ids.size() > 1) {
            throw ApiException.badRequest("Zlecone zadanie ma jednego wykonawcę");
        }
        List<AppUser> allowed = assignable(storeId, userId);
        return ids.stream().map(id -> allowed.stream().filter(user -> user.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "Temu kontu nie zlecisz zadania: nie ma dostępu do sklepu albo nie stoi w hierarchii niżej")))
                .toList();
    }

    /** Całemu sklepowi zleca ten, kto może zlecać każdemu w sklepie (właściciel, Aderon, włączone zlecanie). */
    private boolean assignsToStore(UUID userId, UUID storeId) {
        StoreRoleService.Actor actor = ranks.actor(userId, storeId);
        return actor.admin() || actor.user().isCanAssignTasks();
    }

    @Transactional
    public TaskResponse update(UUID storeId, UUID id, TaskRequest request, UUID userId) {
        StoreTask task = managed(storeId, id, userId);
        AppUser previous = task.getAssignee();
        AppUser assignee = targets(storeId, request, userId, false).getFirst();
        task.describe(request.title().strip(), trim(request.description()), request.dueDate(), request.priority(),
                assignee);
        events.save(new StoreTaskEvent(task, TaskEventKind.EDITED, users.getReferenceById(userId), null));
        if (assignee != null && (previous == null || !previous.getId().equals(assignee.getId()))
                && !assignee.getId().equals(userId)) {
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

    /**
     * „Wszyscy już zrobili”: zamyka naraz otwarte kopie zlecenia kilku osobom. Może to zrobić każdy z wykonawców
     * grupy i zlecający; zlecający dostaje jedno powiadomienie.
     */
    @Transactional
    public TaskResponse doneAll(UUID storeId, UUID id, String comment, UUID userId) {
        StoreTask task = active(storeId, id);
        if (!groupMember(task, userId) && !manages(task, userId)) {
            throw ApiException.forbidden("To zadanie wykonuje kto inny");
        }
        if (task.getGroupId() == null) {
            throw ApiException.badRequest("To zadanie ma jednego wykonawcę");
        }
        AppUser user = users.getReferenceById(userId);
        String note = trim(comment) == null ? "Wszyscy już zrobili" : trim(comment);
        List<StoreTask> open = tasks.findOfGroups(List.of(task.getGroupId())).stream()
                .filter(copy -> copy.getStatus() == TaskStatus.OPEN).toList();
        if (open.isEmpty()) {
            throw ApiException.conflict("Wszystkie kopie są już oznaczone jako wykonane");
        }
        open.forEach(copy -> markDone(copy, user, note, false));
        AppUser creator = task.getCreatedBy();
        if (creator != null && !creator.getId().equals(userId)) {
            push.notify(List.of(creator.getId()), "Zadanie wykonane przez wszystkich", task.getTitle(),
                    Map.of("url", MOBILE_URL));
        }
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
        if (task.getAssignee() != null && !task.getAssignee().getId().equals(userId)) {
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
        markDone(task, user, comment, true);
    }

    private void markDone(StoreTask task, AppUser user, String comment, boolean notify) {
        task.markDone(user, comment);
        events.save(new StoreTaskEvent(task, TaskEventKind.DONE, user, comment));
        AppUser creator = task.getCreatedBy();
        if (notify && creator != null && !creator.getId().equals(user.getId())) {
            String who = task.getAssignee() == null ? user.getDisplayName() : task.getAssignee().getDisplayName();
            push.notify(List.of(creator.getId()), "Zadanie wykonane", who + ": " + task.getTitle(),
                    Map.of("url", MOBILE_URL));
        }
    }

    /** Wykonawca zadania; zadanie całego sklepu wykonuje każdy z dostępem do sklepu (sprawdza je kontroler). */
    private static boolean isAssignee(StoreTask task, UUID userId) {
        return task.isForStore() || (task.getAssignee() != null && task.getAssignee().getId().equals(userId));
    }

    /**
     * Komu można zlecić: właściciel, Aderon i konto z włączonym przez właściciela zlecaniem każdemu z dostępem do
     * sklepu, pozostali osobom niżej w hierarchii sklepu (i sobie).
     */
    private List<AppUser> assignable(UUID storeId, UUID userId) {
        StoreRoleService.Actor actor = ranks.actor(userId, storeId);
        List<AppUser> all = tasks.findAssignees(storeId);
        if (actor.admin() || actor.user().isCanAssignTasks()) {
            return all;
        }
        return all.stream().filter(user -> user.getId().equals(userId)
                || (!user.isOwner() && actor.above(user.rankIn(storeId)))).toList();
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
        if (!isAssignee(task, userId) && !groupMember(task, userId) && !manages(task, userId)) {
            throw ApiException.notFound("Nie ma takiego zadania");
        }
        return task;
    }

    /** Wykonawca innej kopii tego samego zlecenia (widzi kopie, może oznaczyć „wszyscy już zrobili”). */
    private boolean groupMember(StoreTask task, UUID userId) {
        return task.getGroupId() != null && tasks.findOfGroups(List.of(task.getGroupId())).stream()
                .anyMatch(copy -> isAssignee(copy, userId));
    }

    private void requireAssigneeOrManager(StoreTask task, UUID userId) {
        if (!isAssignee(task, userId) && !manages(task, userId)) {
            throw ApiException.forbidden("To zadanie wykonuje kto inny");
        }
    }

    /** Właściciel albo Aderon sklepu: widzi i prowadzi wszystkie zadania sklepu. */
    private boolean isOwner(UUID userId, UUID storeId) {
        return ranks.administers(userId, storeId);
    }

    /** Czy konto zleca zadania w sklepie: właściciel, Aderon, włączone zlecanie albo ktoś pod nim w hierarchii. */
    private boolean assigns(UUID userId, UUID storeId) {
        StoreRoleService.Actor actor = ranks.actor(userId, storeId);
        if (actor.admin() || actor.user().mayAssignTasks()) {
            return true;
        }
        StoreRole rank = actor.rank();
        return rank != null && !rank.isOutsideHierarchy() && rank.getLowerRanks() > 0;
    }

    /** Właściciel widzi wszystkie zlecone, inny zlecający tylko swoje ({@code null} = bez zawężenia). */
    private UUID creatorFilter(UUID userId, UUID storeId) {
        return isOwner(userId, storeId) ? null : userId;
    }

    /** Zadaniem zarządza właściciel, a inny zlecający tylko tym, które sam zlecił (dopóki ma zlecanie). */
    private boolean manages(StoreTask task, UUID userId) {
        UUID storeId = task.getStore().getId();
        return manages(task, userId, isOwner(userId, storeId), assigns(userId, storeId));
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
        UUID storeId = found.get(0).getStore().getId();
        boolean owner = isOwner(userId, storeId);
        boolean assigner = assigns(userId, storeId);
        Map<UUID, List<TaskResponse.Item>> byTask = items.findOfTasks(found.stream().map(StoreTask::getId).toList())
                .stream().collect(Collectors.groupingBy(item -> item.getTask().getId(),
                        Collectors.mapping(mapper::toItem, Collectors.toList())));
        Map<UUID, TaskResponse.Group> groups = groups(found);
        Map<UUID, List<StoreTaskAttachmentRepository.Summary>> filesByTask = attachments
                .findOfTasks(found.stream().map(StoreTask::getId).toList()).stream()
                .collect(Collectors.groupingBy(StoreTaskAttachmentRepository.Summary::getTaskId));
        List<TaskResponse> result = new ArrayList<>(found.size());
        for (StoreTask task : found) {
            boolean manager = manages(task, userId, owner, assigner);
            List<TaskResponse.Attachment> files = filesByTask.getOrDefault(task.getId(), List.of()).stream()
                    .map(file -> toAttachment(file, manager, userId)).toList();
            result.add(mapper.toResponse(task, group(groups, task), byTask.getOrDefault(task.getId(), List.of()),
                    files, manager, isAssignee(task, userId)));
        }
        return result;
    }

    private TaskResponse response(StoreTask task, UUID userId) {
        List<TaskResponse.Item> list = task.getItems().stream().map(mapper::toItem).toList();
        boolean manager = manages(task, userId);
        List<TaskResponse.Attachment> files = attachments.findOfTasks(List.of(task.getId())).stream()
                .map(file -> toAttachment(file, manager, userId)).toList();
        return mapper.toResponse(task, group(groups(List.of(task)), task), list, files, manager,
                isAssignee(task, userId));
    }

    private static TaskResponse.Group group(Map<UUID, TaskResponse.Group> groups, StoreTask task) {
        return task.getGroupId() == null ? null : groups.get(task.getGroupId());
    }

    /** Postęp zleceń kilku osobom: grupa → ile kopii, ile wykonanych i kto. */
    private Map<UUID, TaskResponse.Group> groups(List<StoreTask> found) {
        List<UUID> groupIds = found.stream().map(StoreTask::getGroupId).filter(Objects::nonNull).distinct().toList();
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return tasks.findOfGroups(groupIds).stream().collect(Collectors.groupingBy(StoreTask::getGroupId,
                Collectors.collectingAndThen(Collectors.toList(), copies -> new TaskResponse.Group(
                        copies.getFirst().getGroupId(), copies.size(),
                        (int) copies.stream().filter(copy -> copy.getStatus() != TaskStatus.OPEN).count(),
                        copies.stream().map(copy -> new TaskResponse.GroupMember(copy.getId(),
                                copy.getAssignee() == null ? null : mapper.toPerson(copy.getAssignee()),
                                copy.getStatus())).toList()))));
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
