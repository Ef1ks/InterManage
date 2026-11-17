package com.intermanage.backend.store.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.store.entity.StoreFileQueueItem;

public interface StoreFileQueueRepository extends JpaRepository<StoreFileQueueItem, UUID> {

    /** Plik w kolejce bez treści, do listy. */
    interface Summary {
        UUID getId();

        String getFolderKey();

        String getFolderLabel();

        String getFileName();

        int getSizeBytes();

        Instant getCreatedAt();

        String getCreatedBy();
    }

    /** Pliki czekające na zapis, w kolejności dodania (tak też się zapisują). */
    @Query("""
            select q.id as id, q.folderKey as folderKey, q.folderLabel as folderLabel, q.fileName as fileName,
                q.sizeBytes as sizeBytes, q.createdAt as createdAt, u.displayName as createdBy
            from StoreFileQueueItem q left join q.createdBy u
            where q.storeId = :storeId and q.savedAt is null and q.removedAt is null
            order by q.createdAt, q.id""")
    List<Summary> findPending(UUID storeId);

    @Query("""
            select count(q) from StoreFileQueueItem q
            where q.storeId = :storeId and q.savedAt is null and q.removedAt is null""")
    long countPending(UUID storeId);
}
