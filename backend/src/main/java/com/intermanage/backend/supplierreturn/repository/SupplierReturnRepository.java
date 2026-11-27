package com.intermanage.backend.supplierreturn.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.supplierreturn.entity.SupplierReturn;
import com.intermanage.backend.supplierreturn.entity.SupplierReturnEvent;
import com.intermanage.backend.supplierreturn.entity.SupplierReturnLine;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, UUID> {

    @Query("""
            select r from SupplierReturn r left join fetch r.supplier left join fetch r.createdBy
            left join fetch r.deliveryDocument left join fetch r.correctionDocument left join fetch r.ksefInvoice
            where r.storeId = :storeId and r.deletedAt is null order by r.createdAt desc""")
    List<SupplierReturn> findLatest(UUID storeId, Limit limit);

    @Query("""
            select count(r) from SupplierReturn r where r.storeId = :storeId and r.deletedAt is null
            and r.status in ('NEW', 'REPORTED', 'IN_PROGRESS', 'ACCEPTED')""")
    long countOpen(UUID storeId);

    @Query("select l from SupplierReturnLine l where l.returnId in :ids order by l.returnId, l.position")
    List<SupplierReturnLine> findLines(Collection<UUID> ids);

    @Query("""
            select e from SupplierReturnEvent e left join fetch e.createdBy
            where e.returnId = :id order by e.createdAt""")
    List<SupplierReturnEvent> findEvents(UUID id);

    /** Dokument z liczbą artykułów wspólnych ze zwrotem (po ITM8 albo EAN-ie bez zer wiodących). */
    interface DocumentCandidate {
        UUID getId();

        String getType();

        String getNumber();

        LocalDateTime getDocumentDate();

        String getSupplierDocumentNumber();

        java.math.BigDecimal getPurchaseValue();

        long getCommon();
    }

    String DOCUMENT_CANDIDATES = """
            select d.id as id, d.type as type, d.number as number, d.document_date as documentDate,
                   d.supplier_document_number as supplierDocumentNumber, d.purchase_value as purchaseValue,
                   count(distinct coalesce(l.itm8, l.ean)) as common
            from documents d
            join document_lines l on l.document_id = d.id
            where d.store_id = :storeId and d.type in (:types) and d.error_marked_at is null
              and d.document_date >= :since
              and (cast(:supplierCode as text) is null
                   or ltrim(l.supplier_code, '0') = ltrim(cast(:supplierCode as text), '0'))
              and (l.itm8 in (:itm8s) or ltrim(l.ean, '0') in (:eans))
            """;

    /** Przyjęcia albo korekty sklepu z artykułami zwrotu, najpierw z największą liczbą wspólnych. */
    @Query(value = DOCUMENT_CANDIDATES + """
            group by d.id
            order by common desc, d.document_date desc
            limit 15
            """, nativeQuery = true)
    List<DocumentCandidate> findDocumentCandidates(UUID storeId, Collection<String> types, LocalDateTime since,
            String supplierCode, Collection<String> itm8s, Collection<String> eans);

    /** Dokumenty przypięte już do innego (nieusuniętego) zwrotu jako korekta. */
    @Query(value = """
            select r.correction_document_id from supplier_returns r
            where r.store_id = :storeId and r.deleted_at is null and r.id <> :returnId
              and r.correction_document_id is not null""", nativeQuery = true)
    List<UUID> findTakenCorrectionDocuments(UUID storeId, UUID returnId);

    @Query(value = """
            select r.ksef_invoice_id from supplier_returns r
            where r.store_id = :storeId and r.deleted_at is null and r.id <> :returnId
              and r.ksef_invoice_id is not null""", nativeQuery = true)
    List<UUID> findTakenInvoices(UUID storeId, UUID returnId);

    /** Korekta z KSeF od dostawcy (po NIP-ie sprzedawcy) w firmie sklepu, z XML-em zapisanym, jeśli jest. */
    interface InvoiceCandidate {
        UUID getId();

        String getInvoiceNumber();

        LocalDate getIssueDate();

        java.math.BigDecimal getNetAmount();

        byte[] getXml();
    }

    @Query(value = """
            select i.id as id, i.invoice_number as invoiceNumber, i.issue_date as issueDate,
                   i.net_amount as netAmount, x.content as xml
            from ksef_invoices i
            left join ksef_invoice_xml x on x.invoice_id = i.id
            where i.nip = :companyNip and i.seller_nip = :sellerNip and i.invoice_type like 'Kor%'
              and i.issue_date >= :since
            order by i.issue_date desc
            limit 30
            """, nativeQuery = true)
    List<InvoiceCandidate> findInvoiceCandidates(String companyNip, String sellerNip, LocalDate since);

    /** Ostatni dostawca każdego artykułu z przyjęć PZ, do podpowiedzi dostawcy zwrotu. */
    @Query(value = """
            select ltrim(supplier_code, '0') from (
              select distinct on (l.itm8) l.supplier_code
              from document_lines l
              join documents d on d.id = l.document_id and d.error_marked_at is null
              where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and l.itm8 in (:itm8s)
                and l.supplier_code is not null
              order by l.itm8, d.document_date desc, d.number desc
            ) last
            group by ltrim(supplier_code, '0')
            order by count(*) desc
            limit 1
            """, nativeQuery = true)
    List<String> findMostCommonLastSupplier(UUID storeId, Collection<String> itm8s);

    @Query(value = """
            select s.id from suppliers s
            where s.store_id = :storeId and s.code is not null and ltrim(s.code, '0') = :code
            limit 1""", nativeQuery = true)
    List<UUID> findSupplierByCode(UUID storeId, String code);
}
