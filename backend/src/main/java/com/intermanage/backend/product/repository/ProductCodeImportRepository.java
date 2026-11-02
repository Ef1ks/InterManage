package com.intermanage.backend.product.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductCodeImport;

/** Wgrania multikodów i same pary ITM8 + EAN ({@code product_codes}, bez encji: zapis i liczniki zapytaniami). */
public interface ProductCodeImportRepository extends JpaRepository<ProductCodeImport, UUID> {

    @Query("select i from ProductCodeImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.importedAt desc, i.id desc")
    List<ProductCodeImport> findLatest(UUID storeId, Limit limit);

    @Query("select max(i.reportGeneratedAt) from ProductCodeImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);

    /** Blokada importu multikodów sklepu do końca transakcji, jak przy innych importach. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);

    interface MergeCounts {
        long getCreated();

        long getUpdated();
    }

    /**
     * Zapisuje paczkę par z pliku jednym zapytaniem: nowe pary są dopisywane, znane dostają dane z pliku i bieżący
     * import (i znów obowiązują). {@code updated} liczy znane, w których coś się zmieniło, także powrót pary
     * nieobowiązującej (porównanie widzi stan sprzed zapytania).
     * Pary w tablicach muszą być unikalne - pilnuje tego parser.
     */
    @Query(value = """
            with input as (
                select *
                from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[]), cast(:names as varchar[]),
                            cast(:prios as boolean[]), cast(:saleUnits as varchar[]),
                            cast(:salePacks as numeric[]), cast(:priceFactors as numeric[]),
                            cast(:salePrices as numeric[]), cast(:removalDates as date[]),
                            cast(:depositCodes as smallint[]))
                     as r(itm8, ean, name, prio, sale_unit, sale_pack, price_factor, sale_price, removal_date,
                          deposit_code)
            ), changed as (
                select count(*) as n
                from input i
                join product_codes c on c.store_id = :storeId and c.itm8 = i.itm8 and c.ean = i.ean
                where (c.name, c.prio, c.sale_unit, c.sale_pack, c.price_factor, c.sale_price, c.removal_date,
                       c.deposit_code, c.current)
                      is distinct from
                      (i.name, i.prio, i.sale_unit, i.sale_pack, i.price_factor, i.sale_price, i.removal_date,
                       i.deposit_code, true)
            ), saved as (
                insert into product_codes (store_id, itm8, ean, name, prio, sale_unit, sale_pack, price_factor,
                                           sale_price, removal_date, deposit_code, first_import_id, last_import_id)
                select :storeId, itm8, ean, name, prio, sale_unit, sale_pack, price_factor, sale_price, removal_date,
                       deposit_code, :importId, :importId
                from input
                on conflict (store_id, itm8, ean) do update
                    set name = excluded.name, prio = excluded.prio, sale_unit = excluded.sale_unit,
                        sale_pack = excluded.sale_pack, price_factor = excluded.price_factor,
                        sale_price = excluded.sale_price, removal_date = excluded.removal_date,
                        deposit_code = excluded.deposit_code, current = true,
                        last_import_id = excluded.last_import_id
                returning (xmax = 0) as created
            )
            select (select count(*) from saved where created) as created, (select n from changed) as updated
            """, nativeQuery = true)
    MergeCounts merge(UUID storeId, UUID importId, String[] itm8s, String[] eans, String[] names, Boolean[] prios,
            String[] saleUnits, BigDecimal[] salePacks, BigDecimal[] priceFactors, BigDecimal[] salePrices,
            LocalDate[] removalDates, Short[] depositCodes);

    /** Pary, których nie było w tym imporcie, przestają obowiązywać (zostają jako historia przypisań). */
    @Modifying
    @Query(value = """
            update product_codes set current = false
            where store_id = :storeId and current and last_import_id <> :importId
            """, nativeQuery = true)
    int retireMissing(UUID storeId, UUID importId);

    /** Pary sklepu, których nie było w tym imporcie (zostają jako historia przypisań). */
    @Query(value = "select count(*) from product_codes c where c.store_id = :storeId and c.last_import_id <> :importId",
            nativeQuery = true)
    long countMissing(UUID storeId, UUID importId);

    interface CurrentCounts {
        long getCodes();

        long getArticles();
    }

    /** Obowiązujące pary i ich artykuły (z najnowszego importu multikodów sklepu). */
    @Query(value = """
            select count(*) as codes, count(distinct c.itm8) as articles
            from product_codes c
            where c.store_id = :storeId and c.current
            """, nativeQuery = true)
    CurrentCounts countCurrent(UUID storeId);
}
