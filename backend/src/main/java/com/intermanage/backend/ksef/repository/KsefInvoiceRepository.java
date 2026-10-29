package com.intermanage.backend.ksef.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.ksef.entity.KsefInvoice;

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

    /** Faktury firmy o numerach spośród podanych (wielkimi literami) - do dopasowania przyjęć po numerze. */
    @Query("""
            select distinct i from KsefInvoice i left join fetch i.internalIds
            where i.nip = :nip and upper(i.invoiceNumber) in :numbers""")
    List<KsefInvoice> findByInvoiceNumbers(String nip, Collection<String> numbers);

    long countByNip(String nip);

    /** Korekty firmy bez odczytanych faktur korygowanych, z zapisanym już XML (odczyt bez KSeF). */
    @Query("""
            select i.id from KsefInvoice i
            where i.nip = :nip and i.invoiceType like 'Kor%' and i.correctedReadAt is null
              and i.sellerNip not in :ignored
              and exists (select 1 from KsefInvoiceXml x where x.invoiceId = i.id)
            order by i.issueDate desc, i.id""")
    List<UUID> findUnreadStoredCorrections(String nip, Collection<String> ignored, Pageable page);

    /** Korekty firmy bez odczytanych faktur korygowanych i bez XML (do pobrania z KSeF), od najnowszej. */
    @Query("""
            select i from KsefInvoice i
            where i.nip = :nip and i.invoiceType like 'Kor%' and i.correctedReadAt is null
              and i.sellerNip not in :ignored
              and not exists (select 1 from KsefInvoiceXml x where x.invoiceId = i.id)
            order by i.issueDate desc, i.id""")
    List<KsefInvoice> findUnreadCorrectionsToDownload(String nip, Collection<String> ignored, Pageable page);

    /** Ile korekt sprzedawcy czeka na odczyt faktur korygowanych. */
    @Query("""
            select count(i) from KsefInvoice i
            where i.nip = :nip and i.sellerNip in :sellerNips and i.invoiceType like 'Kor%'
              and i.correctedReadAt is null""")
    long countUnreadCorrections(String nip, Collection<String> sellerNips);

    /**
     * Korekty firmy, które korygują fakturę o numerze KSeF z {@code ksefNumbers} albo o numerze z {@code numbers}
     * (wielkimi literami); kto co koryguje, sprawdza się potem po {@link KsefInvoice#getCorrectedInvoices()}.
     */
    @Query("""
            select distinct i from KsefInvoice i join i.correctedInvoices r
            where i.nip = :nip and (r.ksefNumber in :ksefNumbers or upper(r.invoiceNumber) in :numbers)""")
    List<KsefInvoice> findCorrectionsOf(String nip, Collection<String> ksefNumbers, Collection<String> numbers);
}
