package com.intermanage.backend.store.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.store.entity.StoreFileQueueItem;

public interface StoreFileQueueRepository extends JpaRepository<StoreFileQueueItem, UUID> {

    /** Plik w kolejce bez treści, do listy. */
    interface Summary {
        UUID getId();

        String getFolderKey();

        String getFolderLabel();

        String getGroupKey();

        String getGroupLabel();

        String getFileName();

        int getSizeBytes();

        Instant getCreatedAt();

        String getCreatedBy();

        String getClaimedBy();

        Instant getClaimExpiresAt();

        Instant getFailedAt();

        String getLastError();
    }

    /** Stan pliku do potwierdzenia zapisu (przeglądarka czeka na agenta). */
    interface Status {
        UUID getId();

        String getFileName();

        String getFolderLabel();

        Instant getSavedAt();

        boolean getSavedByAgent();

        Instant getRemovedAt();

        Instant getFailedAt();

        String getLastError();

        String getClaimedBy();
    }

    String CLAIMED_BY = """
            case when q.claim_expires_at is null or q.claim_expires_at < now() then null
                 when q.claimed_by_agent_id is not null
                     then coalesce((select 'Agent sklepu (' || a.name || ')' from store_agents a
                                    where a.id = q.claimed_by_agent_id), 'Agent sklepu')
                 else (select u.display_name from users u where u.id = q.claimed_by_user_id) end""";

    /** Pliki czekające na zapis, w kolejności dodania (tak też się zapisują). */
    @Query(nativeQuery = true, value = """
            select q.id as id, q.folder_key as folderKey, q.folder_label as folderLabel,
                q.group_key as groupKey, q.group_label as groupLabel, q.file_name as fileName,
                q.size_bytes as sizeBytes, q.created_at as createdAt, u.display_name as createdBy,
            """ + CLAIMED_BY + """
                 as claimedBy,
                case when q.claim_expires_at < now() then null else q.claim_expires_at end as claimExpiresAt,
                q.failed_at as failedAt, q.last_error as lastError
            from store_file_queue q left join users u on u.id = q.created_by
            where q.store_id = :storeId and q.saved_at is null and q.removed_at is null
            order by q.created_at, q.id""")
    List<Summary> findPending(UUID storeId);

    @Query("""
            select count(q) from StoreFileQueueItem q
            where q.storeId = :storeId and q.savedAt is null and q.removedAt is null""")
    long countPending(UUID storeId);

    @Query(nativeQuery = true, value = """
            select q.id as id, q.file_name as fileName, q.folder_label as folderLabel, q.saved_at as savedAt,
                q.saved_by_agent_id is not null as savedByAgent, q.removed_at as removedAt,
                q.failed_at as failedAt, q.last_error as lastError,
            """ + CLAIMED_BY + """
                 as claimedBy
            from store_file_queue q
            where q.store_id = :storeId and q.id = :id""")
    List<Status> findStatus(UUID storeId, UUID id);

    /**
     * Przydziela agentowi najwyżej {@code limit} czekających plików (najstarsze najpierw): tylko bez ważnego przydziału
     * i bez nieudanego zapisu (te zapisuje przeglądarka). Jedno zapytanie, więc dwóch chętnych nie dostanie tego samego.
     * Bez {@code @Modifying}, bo {@code returning} zwraca wiersze jak zapytanie.
     */
    @Query(nativeQuery = true, value = """
            update store_file_queue q
            set claimed_at = now(), claimed_by_agent_id = :agentId, claimed_by_user_id = null,
                claim_expires_at = now() + interval '2 minutes'
            where q.id in (
                select c.id from store_file_queue c
                where c.store_id = :storeId and c.saved_at is null and c.removed_at is null and c.failed_at is null
                  and (c.claim_expires_at is null or c.claim_expires_at < now())
                  and c.folder_key in (:folderKeys)
                order by c.created_at, c.id
                limit :limit
                for update skip locked)
            returning q.id""")
    List<UUID> claimForAgent(UUID storeId, UUID agentId, List<String> folderKeys, int limit);

    /** Przydział jednego pliku osobie (zapis z przeglądarki); 0, gdy plik ma ważny przydział kogoś innego. */
    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue
            set claimed_at = now(), claimed_by_user_id = :userId, claimed_by_agent_id = null,
                claim_expires_at = now() + interval '2 minutes'
            where store_id = :storeId and id = :id and saved_at is null and removed_at is null
              and (claim_expires_at is null or claim_expires_at < now() or claimed_by_user_id = :userId)""")
    int claimForUser(UUID storeId, UUID id, UUID userId);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue
            set saved_at = now(), saved_by_agent_id = :agentId, claim_expires_at = null, failed_at = null,
                last_error = null
            where store_id = :storeId and id = :id and saved_at is null and claimed_by_agent_id = :agentId
              and claim_expires_at is not null""")
    int savedByAgent(UUID storeId, UUID id, UUID agentId);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue
            set saved_at = now(), saved_by = :userId, claim_expires_at = null, failed_at = null, last_error = null
            where store_id = :storeId and id = :id and saved_at is null and claimed_by_user_id = :userId
              and claim_expires_at is not null""")
    int savedByUser(UUID storeId, UUID id, UUID userId);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue
            set claim_expires_at = null, claimed_by_agent_id = null, failed_at = now(), last_error = :error
            where store_id = :storeId and id = :id and saved_at is null and claimed_by_agent_id = :agentId
              and claim_expires_at is not null""")
    int failedByAgent(UUID storeId, UUID id, UUID agentId, String error);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue
            set claim_expires_at = null, claimed_by_user_id = null, failed_at = now(), last_error = :error
            where store_id = :storeId and id = :id and saved_at is null and claimed_by_user_id = :userId
              and claim_expires_at is not null""")
    int failedByUser(UUID storeId, UUID id, UUID userId, String error);

    /** Zdejmuje przydział agenta bez błędu (odpowiedź do agenta nie doszła). */
    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue set claim_expires_at = null, claimed_by_agent_id = null
            where id in (:ids) and saved_at is null and claimed_by_agent_id = :agentId""")
    int release(List<UUID> ids, UUID agentId);

    /** Plik do wczytania makrem, bez treści. */
    interface MacroFile {
        UUID getId();

        String getFileName();

        UUID getCreatedBy();

        UUID getExportId();
    }

    /** Plik zapisany przez agenta czeka na makro, które go wczyta. */
    @Modifying
    @Query(nativeQuery = true, value = """
            update store_file_queue
            set macro_requested_at = now(), macro_job_id = null, product_file_export_id = :exportId
            where id = :id""")
    int requestMacro(UUID id, UUID exportId);

    /** Pliki sklepu czekające na makro, z blokadą: dwa wyzwalacze naraz nie wezmą tych samych. */
    @Query(nativeQuery = true, value = """
            select q.id as id, q.file_name as fileName, q.created_by as createdBy,
                q.product_file_export_id as exportId
            from store_file_queue q
            where q.store_id = :storeId and q.macro_requested_at > now() - make_interval(mins => :minutes)
              and q.macro_job_id is null and q.created_by is not null
            order by q.macro_requested_at, q.id
            for update skip locked""")
    List<MacroFile> lockWaitingForMacro(UUID storeId, int minutes);

    @Query(nativeQuery = true, value = """
            select q.id as id, q.file_name as fileName, q.created_by as createdBy,
                q.product_file_export_id as exportId
            from store_file_queue q
            where q.macro_job_id = :jobId
            order by q.macro_requested_at, q.id
            for update""")
    List<MacroFile> findMacroFiles(UUID jobId);

    Optional<StoreFileQueueItem> findFirstByStoreIdAndFolderKeyAndFileNameOrderByCreatedAtDesc(UUID storeId,
            String folderKey, String fileName);

    @Query(nativeQuery = true, value = """
            select count(*) from store_file_queue where id = :id and macro_requested_at is not null""")
    long countMacroRequested(UUID id);

    @Modifying
    @Query(nativeQuery = true, value = "update store_file_queue set macro_job_id = :jobId where id in (:ids)")
    int assignMacro(List<UUID> ids, UUID jobId);

    @Query("""
            select q from StoreFileQueueItem q
            where q.id in :ids order by q.createdAt, q.id""")
    List<StoreFileQueueItem> findOrdered(List<UUID> ids);
}
