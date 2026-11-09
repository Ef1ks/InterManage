package com.intermanage.backend.download.repository;

import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.download.entity.DownloadFileSeen;

public interface DownloadFileSeenRepository extends JpaRepository<DownloadFileSeen, UUID> {

    @Query("select s.fileId from DownloadFileSeen s where s.userId = :userId")
    Set<UUID> findFileIdsSeenBy(UUID userId);

    /** Zapisuje, że konto zna wersję; ponowne wywołanie tylko uzupełnia datę pierwszego pobrania. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into download_file_seen (id, file_id, user_id, seen_at, downloaded_at)
            values (gen_random_uuid(), :fileId, :userId, now(), case when cast(:downloaded as boolean) then now() end)
            on conflict (file_id, user_id) do update
            set downloaded_at = coalesce(download_file_seen.downloaded_at, excluded.downloaded_at)""")
    void markSeen(UUID fileId, UUID userId, boolean downloaded);
}
