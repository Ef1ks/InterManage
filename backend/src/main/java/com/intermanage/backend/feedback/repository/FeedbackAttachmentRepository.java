package com.intermanage.backend.feedback.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.feedback.entity.FeedbackAttachment;

public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, UUID> {

    /** Opis zrzutu bez treści, do list zgłoszeń. */
    interface Summary {
        UUID getId();

        UUID getReportId();

        String getFileName();

        String getContentType();

        int getSizeBytes();

        Instant getCreatedAt();
    }

    @Query("""
            select a.id as id, a.report.id as reportId, a.fileName as fileName, a.contentType as contentType,
                   a.sizeBytes as sizeBytes, a.createdAt as createdAt
            from FeedbackAttachment a
            where a.report.id in :reportIds
            order by a.createdAt
            """)
    List<Summary> findOfReports(Collection<UUID> reportIds);

    @Query("select a from FeedbackAttachment a where a.report.id = :reportId and a.id = :id")
    Optional<FeedbackAttachment> findInReport(UUID reportId, UUID id);
}
