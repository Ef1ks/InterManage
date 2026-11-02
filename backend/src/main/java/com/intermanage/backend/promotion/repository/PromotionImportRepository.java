package com.intermanage.backend.promotion.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.promotion.entity.PromotionImport;

/** Wgrania raportu promocji i same promocje ({@code promotions}, bez encji: zapis i liczniki zapytaniami). */
public interface PromotionImportRepository extends JpaRepository<PromotionImport, UUID> {

    @Query("select i from PromotionImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.importedAt desc, i.id desc")
    List<PromotionImport> findLatest(UUID storeId, Limit limit);

    @Query("select max(i.reportGeneratedAt) from PromotionImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);

    /** Blokada importu promocji sklepu do końca transakcji, jak przy innych importach. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);

    interface MergeCounts {
        long getCreated();

        long getUpdated();
    }

    /**
     * Zapisuje promocje z pliku jednym zapytaniem: nowe (sklep + ITM8 + okres) są dopisywane, znane dostają ceny
     * i nazwę z pliku oraz bieżący import. {@code updated} liczy znane, w których coś się zmieniło (porównanie widzi
     * stan sprzed zapytania). Klucze w tablicach muszą być unikalne - pilnuje tego parser.
     */
    @Query(value = """
            with input as (
                select *
                from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[]), cast(:names as varchar[]),
                            cast(:startDates as date[]), cast(:endDates as date[]),
                            cast(:purchasePrices as numeric[]), cast(:salePrices as numeric[]),
                            cast(:promoPrices as numeric[]), cast(:marginPercents as numeric[]))
                     as r(itm8, ean, name, start_date, end_date, purchase_price, sale_price, promo_price,
                          margin_percent)
            ), changed as (
                select count(*) as n
                from input i
                join promotions p on p.store_id = :storeId and p.itm8 = i.itm8
                                 and p.start_date = i.start_date and p.end_date = i.end_date
                where (p.ean, p.name, p.catalog_purchase_price, p.regular_sale_price, p.promo_price, p.margin_percent)
                      is distinct from
                      (i.ean, i.name, i.purchase_price, i.sale_price, i.promo_price, i.margin_percent)
            ), saved as (
                insert into promotions (store_id, itm8, ean, name, start_date, end_date, catalog_purchase_price,
                                        regular_sale_price, promo_price, margin_percent, first_import_id,
                                        last_import_id)
                select :storeId, itm8, ean, name, start_date, end_date, purchase_price, sale_price, promo_price,
                       margin_percent, :importId, :importId
                from input
                on conflict (store_id, itm8, start_date, end_date) do update
                    set ean = excluded.ean, name = excluded.name,
                        catalog_purchase_price = excluded.catalog_purchase_price,
                        regular_sale_price = excluded.regular_sale_price, promo_price = excluded.promo_price,
                        margin_percent = excluded.margin_percent, last_import_id = excluded.last_import_id
                returning (xmax = 0) as created
            )
            select (select count(*) from saved where created) as created, (select n from changed) as updated
            """, nativeQuery = true)
    MergeCounts merge(UUID storeId, UUID importId, String[] itm8s, String[] eans, String[] names,
            LocalDate[] startDates, LocalDate[] endDates, BigDecimal[] purchasePrices, BigDecimal[] salePrices,
            BigDecimal[] promoPrices, BigDecimal[] marginPercents);

    /**
     * Promocje z zakresu dat końca, który objął raport, a których w nim nie było (np. odwołane). Zostają w bazie
     * z ostatnim importem, w którym były.
     */
    @Query(value = """
            select count(*) from promotions p
            where p.store_id = :storeId and p.last_import_id <> :importId
              and p.end_date >= :endFrom and p.end_date <= :endTo
            """, nativeQuery = true)
    long countMissing(UUID storeId, UUID importId, LocalDate endFrom, LocalDate endTo);

    /** Promocje, które trwają albo dopiero się zaczną (koniec od podanego dnia). */
    @Query(value = "select count(*) from promotions p where p.store_id = :storeId and p.end_date >= :today",
            nativeQuery = true)
    long countActive(UUID storeId, LocalDate today);

    interface ArticlePromotion {
        LocalDate getStartDate();

        LocalDate getEndDate();

        BigDecimal getCatalogPurchasePrice();

        BigDecimal getRegularSalePrice();

        BigDecimal getPromoPrice();

        BigDecimal getMarginPercent();

        boolean getCancelled();
    }

    /**
     * Promocje artykułu z listy promocji, od najpóźniej kończącej się. {@code cancelled}: promocji zabrakło
     * w nowszym raporcie, którego zakres dat końca ją obejmuje (odwołana).
     */
    @Query(value = """
            select p.start_date as startDate, p.end_date as endDate,
                   p.catalog_purchase_price as catalogPurchasePrice, p.regular_sale_price as regularSalePrice,
                   p.promo_price as promoPrice, p.margin_percent as marginPercent,
                   exists (select 1 from promotion_imports n
                           join promotion_imports l on l.id = p.last_import_id
                           where n.store_id = p.store_id and n.imported_at > l.imported_at
                             and n.end_from <= p.end_date and p.end_date <= n.end_to) as cancelled
            from promotions p
            where p.store_id = :storeId and p.itm8 = :itm8
            order by p.end_date desc, p.start_date desc
            limit 200
            """, nativeQuery = true)
    List<ArticlePromotion> findByArticle(UUID storeId, String itm8);
}
