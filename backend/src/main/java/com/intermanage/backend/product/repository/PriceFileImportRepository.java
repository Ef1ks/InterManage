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

    /** Pliki przeczytane w całości, z promocjami. */
    @Query("select i.fileName from PriceFileImport i where i.store.id = :storeId and i.fileName in :fileNames "
            + "and i.promotionsRead = true")
    List<String> findImportedNames(UUID storeId, Collection<String> fileNames);

    /** Pliki wczytane, zanim serwer zapisywał promocje. */
    @Query("select i from PriceFileImport i where i.store.id = :storeId and i.fileName in :fileNames "
            + "and i.promotionsRead = false")
    List<PriceFileImport> findWithoutPromotions(UUID storeId, Collection<String> fileNames);

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

    @Query("select i from PriceFileImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.fileTime desc, i.importedAt desc")
    List<PriceFileImport> findLatest(UUID storeId, Limit limit);

    @Query("select count(p) from Product p where p.store.id = :storeId and p.promoPrice is not null "
            + "and p.promoTo >= :today and (p.promoFrom is null or p.promoFrom <= :today)")
    long countActivePromotions(UUID storeId, LocalDate today);
}
