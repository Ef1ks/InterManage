package com.intermanage.backend.mea.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.mea.entity.MeaDocument;

public interface MeaDocumentRepository extends JpaRepository<MeaDocument, UUID> {

    Optional<MeaDocument> findByNumber(String number);

    @EntityGraph(attributePaths = "lines")
    Optional<MeaDocument> findWithLinesByNumber(String number);

    List<MeaDocument> findByNumberIn(Collection<String> numbers);

    /**
     * Formularze do pobrania: jeszcze niepobrane, z mniej niż {@code maxAttempts} próbami i bez próby od
     * {@code retryBefore}; najpierw najnowsze promocje (bieżąca praca ważniejsza niż archiwum).
     */
    @Query("""
            select d from MeaDocument d
            where d.fetchedAt is null and d.attempts < :maxAttempts
              and (d.lastAttemptAt is null or d.lastAttemptAt < :retryBefore)
            order by d.promoTo desc nulls last, d.number desc
            """)
    List<MeaDocument> findPending(int maxAttempts, Instant retryBefore, Limit limit);
}
