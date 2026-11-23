package com.intermanage.backend.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.task.entity.TaskPriority;
import com.intermanage.backend.task.entity.TaskStatus;

/**
 * Zadanie z pozycjami. {@code canManage}: zalogowany może je zmieniać, przyjmować i usuwać (zlecający);
 * {@code canComplete}: zalogowany jest wykonawcą (odhacza pozycje i oznacza wykonanie). {@code forStore}: zadanie
 * całego sklepu ({@code assignee} pusty, wykonuje ktokolwiek ze sklepu). {@code group}: zlecenie kilku osobom
 * naraz, każda ma swoją kopię.
 */
public record TaskResponse(
        UUID id,
        String title,
        String description,
        LocalDate dueDate,
        TaskPriority priority,
        TaskStatus status,
        Person assignee,
        boolean forStore,
        Group group,
        Person createdBy,
        Instant createdAt,
        String sourceKind,
        String sourceLabel,
        String sourceUrl,
        Person doneBy,
        Instant doneAt,
        String doneComment,
        Instant acceptedAt,
        String returnComment,
        List<Item> items,
        List<Attachment> attachments,
        int itemCount,
        int checkedCount,
        boolean canManage,
        boolean canComplete) {

    public record Person(UUID id, String displayName) {
    }

    /** Kopie zlecenia kilku osobom: ilu wykonało (oznaczyło albo przyjęte) i kto. */
    public record Group(UUID id, int total, int done, List<GroupMember> members) {
    }

    public record GroupMember(UUID taskId, Person person, TaskStatus status) {
    }

    /** Zrzut ekranu albo zdjęcie; {@code canRemove}: zalogowany je dodał albo zarządza zadaniem. */
    public record Attachment(
            UUID id,
            String fileName,
            String contentType,
            int sizeBytes,
            Instant createdAt,
            String createdByName,
            boolean canRemove) {
    }

    public record Item(
            UUID id,
            String itm8,
            String ean,
            String name,
            String details,
            boolean checked,
            Instant checkedAt,
            String checkedByName,
            String note) {
    }
}
