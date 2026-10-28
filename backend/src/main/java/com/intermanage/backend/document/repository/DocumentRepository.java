package com.intermanage.backend.document.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;

/** Listy dokumentów stronami: {@link JpaSpecificationExecutor} z filtrem z {@link DocumentSearch}. */
public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document>,
        DocumentTotalsRepository {

    /** Liczba dokumentów i data najnowszego dla każdego typu (kafelki w module Dokumenty). */
    interface TypeCount {
        DocumentType getType();

        long getDocumentCount();

        LocalDateTime getLastDocumentDate();
    }

    @Query("""
            select d.type as type, count(d) as documentCount, max(d.documentDate) as lastDocumentDate
            from Document d where d.store.id = :storeId group by d.type""")
    List<TypeCount> countByType(UUID storeId);

    /** Dokumenty z importowanego pliku, które już są w bazie, razem z liniami do porównania. */
    @Query("""
            select distinct d from Document d left join fetch d.lines
            where d.store.id = :storeId and d.type = :type and d.number in :numbers""")
    List<Document> findWithLines(UUID storeId, DocumentType type, Collection<String> numbers);

    @Query("select d from Document d left join fetch d.lines where d.id = :id and d.store.id = :storeId and d.type = :type")
    Optional<Document> findWithLines(UUID storeId, DocumentType type, UUID id);

    @Query("select d from Document d where d.id = :id and d.store.id = :storeId and d.type = :type")
    Optional<Document> findHeader(UUID storeId, DocumentType type, UUID id);

    @Query("select d from Document d where d.id = :id and d.store.id = :storeId")
    Optional<Document> findHeader(UUID storeId, UUID id);

    /** Przyjęcia PZ z numerem dokumentu dostawcy spośród podanych (wielkimi literami) - faktury KSeF już wprowadzone. */
    @Query("""
            select d from Document d where d.store.id = :storeId and d.type = com.intermanage.backend.document.entity.DocumentType.PZ
            and upper(d.supplierDocumentNumber) in :numbers order by d.documentDate""")
    List<Document> findDeliveriesBySupplierDocument(UUID storeId, Collection<String> numbers);

    /** Jak wyżej, w podanych sklepach (sklepach firmy), razem ze sklepem. */
    @Query("""
            select d from Document d join fetch d.store where d.store.id in :storeIds
            and d.type = com.intermanage.backend.document.entity.DocumentType.PZ
            and upper(d.supplierDocumentNumber) in :numbers order by d.documentDate""")
    List<Document> findCompanyDeliveriesBySupplierDocument(Collection<UUID> storeIds, Collection<String> numbers);

    @Query("select distinct d from Document d left join fetch d.lines where d.id in :ids and d.store.id = :storeId")
    List<Document> findWithLines(UUID storeId, Collection<UUID> ids);

    /** Dokumenty typu z liniami, z datą w przedziale [from, to), po dacie i numerze (raport przyjęć za okres). */
    @Query("""
            select distinct d from Document d left join fetch d.lines
            where d.store.id = :storeId and d.type = :type and d.documentDate >= :from and d.documentDate < :to
            order by d.documentDate, d.number""")
    List<Document> findWithLinesBetween(UUID storeId, DocumentType type, LocalDateTime from, LocalDateTime to);

    /** Nazwy dostawcy o kodzie z dokumentów sklepu, od najnowszego (gdy nie ma go na liście dostawców). */
    @Query("""
            select d.supplierName from Document d
            where d.store.id = :storeId and d.supplierCode = :code and d.supplierName is not null
            order by d.documentDate desc""")
    List<String> findSupplierNames(UUID storeId, String code, Limit limit);

    /**
     * Przyjęcia PZ z okresu [from, to), które mogły się zmienić po {@code since}: dodane albo zmienione importem,
     * z korektą przypiętą lub odpiętą później, albo z przypiętą korektą zmienioną importem później.
     */
    @Query("""
            select d.id from Document d
            where d.store.id = :storeId and d.type = com.intermanage.backend.document.entity.DocumentType.PZ
              and d.documentDate >= :from and d.documentDate < :to
              and (d.createdAt > :since or d.updatedAt > :since or exists (
                  select 1 from DeliveryCorrection c where c.delivery = d
                  and (c.linkedAt > :since or c.unlinkedAt > :since
                      or (c.unlinkedAt is null and c.correction.updatedAt > :since))))""")
    List<UUID> findChangedDeliveryIds(UUID storeId, LocalDateTime from, LocalDateTime to, Instant since);

    /**
     * Dokumenty typów z liniami od dostawcy z podanego okresu: wspólny dostawca dokumentu albo, gdy linie mają
     * różnych, któraś linia od niego (podpowiedzi przy przypinaniu korekt do przyjęć).
     */
    @Query("""
            select distinct d from Document d left join fetch d.lines
            where d.store.id = :storeId and d.type in :types and d.documentDate between :from and :to
              and (d.supplierCode = :supplierCode or (d.supplierCode is null and exists (
                  select 1 from DocumentLine l where l.document = d and l.supplierCode = :supplierCode)))""")
    List<Document> findBySupplierWithLines(UUID storeId, Collection<DocumentType> types, String supplierCode,
            LocalDateTime from, LocalDateTime to);

    /** Suma wartości zakupu dokumentów jednego dnia. */
    interface DayValue {
        LocalDate getDay();

        BigDecimal getPurchaseValue();
    }

    /**
     * Wartość zakupu dokumentów typu dzień po dniu (wg daty dokumentu), {@code from} i {@code to} włącznie; dni bez
     * dokumentów nie ma w wyniku. Wydania z magazynu (np. reprezentacja) mają wartość ze znakiem minus.
     */
    @Query(value = """
            select cast(d.document_date as date) as day, sum(d.purchase_value) as purchase_value
            from documents d
            where d.store_id = :storeId and d.type = :type
              and d.document_date >= cast(:from as timestamp) and d.document_date < cast(:to as timestamp) + interval '1 day'
            group by 1
            order by 1
            """, nativeQuery = true)
    List<DayValue> sumPurchaseValueByDay(UUID storeId, String type, LocalDate from, LocalDate to);

    /**
     * Blokada importu dokumentów sklepu do końca transakcji (dwa równoczesne importy tego samego pliku
     * nie mogą dodać tego samego dokumentu dwa razy). Funkcja zwraca {@code void}, więc owijamy ją w zapytanie.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);
}
