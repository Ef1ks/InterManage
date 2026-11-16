package com.intermanage.backend.download.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.download.entity.DownloadShortcut;

public interface DownloadShortcutRepository extends JpaRepository<DownloadShortcut, UUID> {

    /** Opis skrótu bez treści, do listy. */
    interface Summary {
        UUID getId();

        String getName();

        String getTarget();

        int getSizeBytes();

        Instant getUploadedAt();

        String getUploadedBy();
    }

    @Query("""
            select s.id as id, s.name as name, s.target as target, s.sizeBytes as sizeBytes,
                s.createdAt as uploadedAt, u.displayName as uploadedBy
            from DownloadShortcut s left join s.createdBy u where s.removedAt is null
            order by lower(s.name)""")
    List<Summary> findActiveSummaries();

    @Query("select s from DownloadShortcut s where s.removedAt is null order by lower(s.name)")
    List<DownloadShortcut> findActive();

    @Query("""
            select count(s) > 0 from DownloadShortcut s
            where s.removedAt is null and lower(s.name) = lower(:name) and s.id <> :exceptId""")
    boolean existsActiveName(String name, UUID exceptId);
}
