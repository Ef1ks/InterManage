package com.intermanage.backend.product.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.PriceFileImport;

public interface PriceFileImportRepository extends JpaRepository<PriceFileImport, UUID> {

    /** Pliki przeczytane w całości, z promocjami i cenami po EAN-ie. */
    @Query("select i.fileName from PriceFileImport i where i.store.id = :storeId and i.fileName in :fileNames "
            + "and i.promotionsRead = true and i.pricesRead = true")
    List<String> findImportedNames(UUID storeId, Collection<String> fileNames);

    /** Pliki wczytane, zanim serwer zapisywał promocje albo ceny po EAN-ie. */
    @Query("select i from PriceFileImport i where i.store.id = :storeId and i.fileName in :fileNames "
            + "and (i.promotionsRead = false or i.pricesRead = false)")
    List<PriceFileImport> findPartlyRead(UUID storeId, Collection<String> fileNames);

    /**
     * Promocje artykułów z pliku jednym zapytaniem; ta sama promocja z nowszego pliku poprawia cenę regularną, ze
     * starszego niczego nie zmienia. Artykuły w tablicach są unikalne.
     */
    @Modifying
    @Query(value = """
            insert into till_promotions (store_id, itm8, promo_from, promo_to, promo_price, regular_price, file_time)
            select :storeId, itm8, promo_from, promo_to, promo_price, regular_price, :fileTime
            from unnest(cast(:itm8s as varchar[]), cast(:promoFroms as date[]), cast(:promoTos as date[]),
                        cast(:promoPrices as numeric[]), cast(:regularPrices as numeric[]))
                 as r(itm8, promo_from, promo_to, promo_price, regular_price)
            on conflict (store_id, itm8, promo_from, promo_to, promo_price) do update
                set regular_price = excluded.regular_price, file_time = excluded.file_time
                where excluded.file_time >= till_promotions.file_time
            """, nativeQuery = true)
    void mergeTillPromotions(UUID storeId, LocalDateTime fileTime, String[] itm8s, LocalDate[] promoFroms,
            LocalDate[] promoTos, BigDecimal[] promoPrices, BigDecimal[] regularPrices);

    /** Wszystkie linie pliku jednym zapytaniem; EAN powtórzony w pliku albo plik wysłany drugi raz niczego nie zmienia. */
    @Modifying
    @Query(value = """
            insert into till_prices (store_id, ean, file_time, itm8, sale_price, promo_price, promo_from, promo_to,
                                     price_file_id)
            select :storeId, ean, :fileTime, itm8, sale_price, promo_price, promo_from, promo_to, :priceFileId
            from unnest(cast(:eans as varchar[]), cast(:itm8s as varchar[]), cast(:salePrices as numeric[]),
                        cast(:promoPrices as numeric[]), cast(:promoFroms as date[]), cast(:promoTos as date[]))
                 as r(ean, itm8, sale_price, promo_price, promo_from, promo_to)
            on conflict do nothing
            """, nativeQuery = true)
    void saveTillPrices(UUID storeId, UUID priceFileId, LocalDateTime fileTime, String[] eans, String[] itm8s,
            BigDecimal[] salePrices, BigDecimal[] promoPrices, LocalDate[] promoFroms, LocalDate[] promoTos);

    interface TillPriceChange {
        String getEan();

        LocalDateTime getFileTime();

        BigDecimal getPreviousSalePrice();

        BigDecimal getSalePrice();

        BigDecimal getPreviousPromoPrice();

        BigDecimal getPromoPrice();

        LocalDate getPromoFrom();

        LocalDate getPromoTo();

        String getFileName();
    }

    /**
     * Zmiany ceny na kasie od najnowszej, osobno dla każdego EAN-u: linie artykułu i jego EAN-ów {@code eans} (EAN bywa
     * przenoszony między artykułami), z których zostają pierwsza linia EAN-u i linie inne niż poprzednia.
     */
    @Query(value = """
            select ean, fileTime, previousSalePrice, salePrice, previousPromoPrice, promoPrice, promoFrom, promoTo,
                   fileName
            from (
                select t.ean as ean, t.file_time as fileTime, t.sale_price as salePrice, t.promo_price as promoPrice,
                       t.promo_from as promoFrom, t.promo_to as promoTo, f.file_name as fileName,
                       lag(t.sale_price) over w as previousSalePrice, lag(t.promo_price) over w as previousPromoPrice,
                       lag(t.promo_from) over w as lastPromoFrom, lag(t.promo_to) over w as lastPromoTo,
                       row_number() over w as position
                from till_prices t
                join price_file_imports f on f.id = t.price_file_id
                where t.store_id = :storeId and (t.itm8 = :itm8 or t.ean = any(cast(:eans as varchar[])))
                window w as (partition by t.ean order by t.file_time)
            ) lines
            where position = 1 or salePrice is distinct from previousSalePrice
               or promoPrice is distinct from previousPromoPrice or promoFrom is distinct from lastPromoFrom
               or promoTo is distinct from lastPromoTo
            order by fileTime desc, ean
            limit :limit
            """, nativeQuery = true)
    List<TillPriceChange> findTillPriceChanges(UUID storeId, String itm8, String[] eans, int limit);

    @Query("select i from PriceFileImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.fileTime desc, i.importedAt desc")
    List<PriceFileImport> findLatest(UUID storeId, Limit limit);

    @Query("select count(p) from Product p where p.store.id = :storeId and p.promoPrice is not null "
            + "and p.promoTo >= :today and (p.promoFrom is null or p.promoFrom <= :today)")
    long countActivePromotions(UUID storeId, LocalDate today);
}
