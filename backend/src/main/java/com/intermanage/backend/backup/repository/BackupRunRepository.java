package com.intermanage.backend.backup.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.backup.entity.BackupRun;
import com.intermanage.backend.backup.entity.BackupStatus;

public interface BackupRunRepository extends JpaRepository<BackupRun, UUID> {

    @EntityGraph(attributePaths = "startedBy")
    List<BackupRun> findByOrderByStartedAtDesc(Limit limit);

    /** Automatyczne próby od podanej chwili (dzisiejsza godzina kopii), najnowsza pierwsza. */
    List<BackupRun> findByManualFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(Instant from);

    /** Kopie leżące na Dysku (udane, jeszcze nie w koszu), od najnowszej: ponad limit idą do kosza. */
    @Query("""
            select r from BackupRun r
            where r.status = com.intermanage.backend.backup.entity.BackupStatus.SUCCEEDED
              and r.driveFileId is not null and r.trashedAt is null
            order by r.startedAt desc""")
    List<BackupRun> findOnDrive();

    boolean existsByStatus(BackupStatus status);

    /** Próby przerwane restartem serwera (proces kopii nie przeżywa restartu). */
    @Modifying
    @Query("""
            update BackupRun r set r.status = com.intermanage.backend.backup.entity.BackupStatus.FAILED,
                r.finishedAt = :now, r.error = :error
            where r.status = com.intermanage.backend.backup.entity.BackupStatus.RUNNING""")
    int failRunning(Instant now, String error);
}
