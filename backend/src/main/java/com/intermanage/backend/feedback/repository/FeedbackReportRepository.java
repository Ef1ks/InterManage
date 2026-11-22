package com.intermanage.backend.feedback.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.feedback.entity.FeedbackReport;
import com.intermanage.backend.feedback.entity.FeedbackStatus;

public interface FeedbackReportRepository extends JpaRepository<FeedbackReport, UUID> {

    /** Zgłoszenia od najnowszego; {@code status} null = wszystkie. */
    @Query("""
            select r from FeedbackReport r
            left join fetch r.store left join fetch r.createdBy left join fetch r.statusChangedBy
            where r.deletedAt is null and (:status is null or r.status = :status)
            order by r.createdAt desc
            """)
    List<FeedbackReport> findActive(FeedbackStatus status);

    /** Własne zgłoszenia pracownika (ostatnie 50). */
    @Query("""
            select r from FeedbackReport r
            left join fetch r.store left join fetch r.createdBy left join fetch r.statusChangedBy
            where r.deletedAt is null and r.createdBy.id = :userId
            order by r.createdAt desc
            limit 50
            """)
    List<FeedbackReport> findOfUser(UUID userId);

    @Query("select r.status as status, count(r) as count from FeedbackReport r where r.deletedAt is null group by r.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        FeedbackStatus getStatus();

        long getCount();
    }
}
