package com.intermanage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.KsefInvoice;

/** Lista faktur stronami: {@link JpaSpecificationExecutor} z filtrem z {@link KsefInvoiceSearch}. */
public interface KsefInvoiceRepository extends JpaRepository<KsefInvoice, UUID>, JpaSpecificationExecutor<KsefInvoice>,
        KsefInvoiceTotalsRepository {

    /** IDWew z pobranych faktur firmy - do wyboru w ustawieniach sklepu. */
    interface SeenInternalId {
        String getInternalId();

        String getName();

        long getInvoiceCount();
    }

    @Query("""
            select r.internalId as internalId, max(r.name) as name, count(i) as invoiceCount
            from KsefInvoice i join i.internalIds r where i.nip = :nip
            group by r.internalId order by r.internalId""")
    List<SeenInternalId> findSeenInternalIds(String nip);

    @Query("select i from KsefInvoice i left join fetch i.internalIds where i.nip = :nip and i.ksefNumber in :numbers")
    List<KsefInvoice> findByKsefNumbers(String nip, Collection<String> numbers);

    Optional<KsefInvoice> findByIdAndNip(UUID id, String nip);

    long countByNip(String nip);
}
