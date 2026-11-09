package com.intermanage.backend.pricecheck.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.CadencierImport;

/** Wgrania listy cadencier i sama lista ({@code cadencier_items}, bez encji: zapis i odczyt zapytaniami). */
public interface CadencierImportRepository extends JpaRepository<CadencierImport, UUID> {

    @Query("select i from CadencierImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.importedAt desc")
    List<CadencierImport> findLatest(UUID storeId, Limit limit);

    default Optional<CadencierImport> findNewest(UUID storeId) {
        return findLatest(storeId, Limit.of(1)).stream().findFirst();
    }

    /** Blokada importu listy sklepu do końca transakcji, jak przy innych importach. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);

    interface MergeCounts {
        long getAdded();

        long getRemoved();
    }

    /**
     * Zastępuje listę sklepu listą z pliku jednym zapytaniem: kody spoza pliku są usuwane, znane dostają bieżący EAN
     * i import, nowe są dopisywane. Lista z pliku jest jedynym źródłem prawdy. {@code added}/{@code removed}
     * porównują plik z listą sprzed zapytania. ITM8 w tablicy muszą być unikalne - pilnuje tego parser.
     */
    @Query(value = """
            with input as (
                select * from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[])) as r(itm8, ean)
            ), removed as (
                delete from cadencier_items c
                where c.store_id = :storeId and not exists (select 1 from input i where i.itm8 = c.itm8)
                returning 1
            ), saved as (
                insert into cadencier_items (store_id, itm8, ean, import_id)
                select :storeId, itm8, ean, :importId from input
                on conflict (store_id, itm8) do update set ean = excluded.ean, import_id = excluded.import_id
                returning (xmax = 0) as created
            )
            select (select count(*) from saved where created) as added,
                   (select count(*) from removed) as removed
            """, nativeQuery = true)
    MergeCounts merge(UUID storeId, UUID importId, String[] itm8s, String[] eans);

    /** ITM8 artykułów obecnych w cadencier sklepu. */
    @Query(value = "select c.itm8 from cadencier_items c where c.store_id = :storeId", nativeQuery = true)
    List<String> findCurrentItm8s(UUID storeId);

    /** Czy artykuł jest na bieżącej liście cadencier sklepu (po ITM8). */
    @Query(value = "select exists (select 1 from cadencier_items c where c.store_id = :storeId and c.itm8 = :itm8)",
            nativeQuery = true)
    boolean isInCadencier(UUID storeId, String itm8);

    interface CadencierEan {
        String getItm8();

        String getEan();
    }

    /**
     * EAN-y artykułów z cadencier: z listy, wszystkie z par ITM8 + EAN z kartoteki i obowiązujące z multikodów. Dostawca spoza bazy przywozi
     * ten sam towar pod własnym, lokalnym ITM8 (np. 99...), więc jego linię łączy z cadencier dopiero EAN.
     */
    @Query(value = """
            select c.itm8 as itm8, c.ean as ean from cadencier_items c
            where c.store_id = :storeId and c.ean is not null
            union
            select c.itm8, pe.ean from cadencier_items c
            join products p on p.store_id = c.store_id and p.itm8 = c.itm8
            join product_eans pe on pe.product_id = p.id
            where c.store_id = :storeId and pe.ean is not null
            union
            select c.itm8, pc.ean from cadencier_items c
            join product_codes pc on pc.store_id = c.store_id and pc.itm8 = c.itm8 and pc.current
            where c.store_id = :storeId
            """, nativeQuery = true)
    List<CadencierEan> findCurrentEans(UUID storeId);

    /**
     * Przyjęcia PZ sklepu z datą w [from, to) z linią od dostawcy spoza {@code baseSupplierCodes} (kod z linii, bez
     * niego z nagłówka) z artykułem z cadencier po ITM8 albo po EAN-ie (jak {@link #findCurrentEans}, bez zer
     * wiodących, od 8 cyfr). Odsiew w bazie, żeby raport nie wczytywał linii wszystkich przyjęć z okresu.
     */
    @Query(value = """
            with lines as (
                select d.id as document_id, l.itm8, ltrim(btrim(l.ean), '0') as ean
                from documents d join document_lines l on l.document_id = d.id
                where d.store_id = :storeId and d.type = 'PZ' and d.document_date >= :from and d.document_date < :to
                  and (coalesce(l.supplier_code, d.supplier_code) is null
                       or coalesce(l.supplier_code, d.supplier_code) not in (:baseSupplierCodes))
            ), cadencier as (
                select c.itm8, c.ean from cadencier_items c where c.store_id = :storeId
            ), cadencier_eans as (
                select ltrim(btrim(c.ean), '0') as ean from cadencier c where c.ean is not null
                union
                select ltrim(btrim(pe.ean), '0') from cadencier c
                join products p on p.store_id = :storeId and p.itm8 = c.itm8
                join product_eans pe on pe.product_id = p.id
                union
                select ltrim(btrim(pc.ean), '0') from cadencier c
                join product_codes pc on pc.store_id = :storeId and pc.itm8 = c.itm8 and pc.current
            )
            select l.document_id from lines l join cadencier c on c.itm8 = l.itm8
            union
            select l.document_id from lines l join cadencier_eans e on e.ean = l.ean where length(l.ean) >= 8
            """, nativeQuery = true)
    List<UUID> findDeliveriesWithCadencierLines(UUID storeId, Collection<String> baseSupplierCodes,
            LocalDateTime from, LocalDateTime to);
}
