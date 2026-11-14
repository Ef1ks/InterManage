package com.intermanage.backend.cash.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cash.entity.CashDocument;
import com.intermanage.backend.cash.entity.CashDocumentKind;

public interface CashDocumentRepository extends JpaRepository<CashDocument, UUID> {

    Optional<CashDocument> findByIdAndStoreId(UUID id, UUID storeId);

    /** Wszystkie dokumenty sklepu w kolejności raportu: dzień, potem kolejność wystawienia. */
    @Query("""
            select d from CashDocument d left join fetch d.createdBy left join fetch d.cancelledBy
            where d.storeId = :storeId
            order by d.issueDate, d.createdAt
            """)
    List<CashDocument> findAllInOrder(UUID storeId);

    @Query("""
            select coalesce(max(d.sequenceNumber), 0) from CashDocument d
            where d.storeId = :storeId and d.kind = :kind and d.year = :year and d.month = :month
            """)
    int lastNumber(UUID storeId, CashDocumentKind kind, int year, int month);

    /** Operacje „Za co” użyte w sklepie (bez anulowanych dokumentów), od najczęstszej; wiersz = rodzaj, operacja. */
    @Query("""
            select d.kind, d.operation from CashDocument d
            where d.storeId = :storeId and d.cancelledAt is null
            group by d.kind, d.operation
            order by count(d) desc, d.operation
            """)
    List<Object[]> usedOperations(UUID storeId);

    boolean existsByStoreIdAndIssueDateBeforeAndCancelledAtIsNull(UUID storeId, LocalDate date);

    /** Blokada wystawiania dokumentów sklepu do końca transakcji: numeracja bez dziur i powtórzeń. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lock(String key);
}
