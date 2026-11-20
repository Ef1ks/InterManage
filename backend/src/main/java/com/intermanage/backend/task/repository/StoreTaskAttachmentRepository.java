package com.intermanage.backend.task.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.task.entity.StoreTaskAttachment;

public interface StoreTaskAttachmentRepository extends JpaRepository<StoreTaskAttachment, UUID> {

    /** Opis załącznika bez treści, do list zadań. */
    interface Summary {
        UUID getId();

        UUID getTaskId();

        String getFileName();

        String getContentType();

        int getSizeBytes();

        Instant getCreatedAt();

        UUID getCreatedById();

        String getCreatedByName();
    }

    @Query("""
            select a.id as id, a.task.id as taskId, a.fileName as fileName, a.contentType as contentType,
                   a.sizeBytes as sizeBytes, a.createdAt as createdAt, u.id as createdById,
                   u.displayName as createdByName
            from StoreTaskAttachment a left join a.createdBy u
            where a.task.id in :taskIds and a.deletedAt is null
            order by a.createdAt
            """)
    List<Summary> findOfTasks(Collection<UUID> taskIds);

    @Query("select count(a) from StoreTaskAttachment a where a.task.id = :taskId and a.deletedAt is null")
    long countActive(UUID taskId);

    @Query("select a from StoreTaskAttachment a where a.task.id = :taskId and a.id = :id")
    Optional<StoreTaskAttachment> findInTask(UUID taskId, UUID id);
}
