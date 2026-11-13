package com.intermanage.backend.ksef.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.intermanage.backend.ksef.entity.KsefBaseSupplierCode;

public interface KsefBaseSupplierCodeRepository extends JpaRepository<KsefBaseSupplierCode, UUID> {

    List<KsefBaseSupplierCode> findByNipAndDeletedAtIsNull(String nip);

    Optional<KsefBaseSupplierCode> findByNipAndCodeAndDeletedAtIsNull(String nip, String code);

    Optional<KsefBaseSupplierCode> findByIdAndNip(UUID id, String nip);

    /** Kod dostawcy z refaktur firmy (tak jak w XML) z liczbą faktur i datą ostatniej. */
    interface InvoiceCode {
        String getCode();

        long getInvoices();

        LocalDate getLastIssueDate();
    }

    @Query("""
            select i.baseSupplierCode as code, count(i) as invoices, max(i.issueDate) as lastIssueDate
            from KsefInvoice i
            where i.nip = :nip and i.baseSupplierCode is not null
            group by i.baseSupplierCode
            """)
    List<InvoiceCode> findInvoiceCodes(@Param("nip") String nip);
}
