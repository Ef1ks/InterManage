package com.intermanage.backend.pricecheck.repository;

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

    interface CadencierEan {
        String getItm8();

        String getEan();
    }

    /**
     * EAN-y artykułów z cadencier: z listy i wszystkie z par ITM8 + EAN z kartoteki. Dostawca spoza bazy przywozi
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
            """, nativeQuery = true)
    List<CadencierEan> findCurrentEans(UUID storeId);
}
