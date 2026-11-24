package com.intermanage.backend.cadtop.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cadtop.entity.CadTopFile;

public interface CadTopFileRepository extends JpaRepository<CadTopFile, UUID> {

    /** Ten plik z portalu (ścieżka i data zmiany) już mamy, także jako zastąpiony nowszym. */
    boolean existsByRegionAndSourcePathAndSourceUpdatedAt(String region, String sourcePath, String sourceUpdatedAt);

    Optional<CadTopFile> findByRegionAndNrCadAndReplacedAtIsNull(String region, String nrCad);

    List<CadTopFile> findByRegionAndReplacedAtIsNull(String region);

    /** Artykuły pliku jednym zapytaniem; ITM8 w tablicy są unikalne (pilnuje tego parser). */
    @Modifying
    @Query(value = """
            insert into cad_top_articles (file_id, itm8, ean, name, brand, supplier_code, product_type,
                purchase_price, sale_price, pack, vat_percent, top_mark, subfamily_code, category, is_new,
                margin_percent, network_rotation)
            select :fileId, r.*
            from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[]), cast(:names as varchar[]),
                cast(:brands as varchar[]), cast(:suppliers as varchar[]), cast(:types as varchar[]),
                cast(:purchasePrices as numeric[]), cast(:salePrices as numeric[]), cast(:packs as integer[]),
                cast(:vats as numeric[]), cast(:marks as varchar[]), cast(:subfamilies as varchar[]),
                cast(:categories as varchar[]), cast(:news as boolean[]), cast(:margins as numeric[]),
                cast(:rotations as numeric[])) as r
            """, nativeQuery = true)
    int insertArticles(UUID fileId, String[] itm8s, String[] eans, String[] names, String[] brands,
            String[] suppliers, String[] types, BigDecimal[] purchasePrices, BigDecimal[] salePrices,
            Integer[] packs, BigDecimal[] vats, String[] marks, String[] subfamilies, String[] categories,
            Boolean[] news, BigDecimal[] margins, BigDecimal[] rotations);

    interface Counts {
        UUID getFileId();

        int getBtop();

        int getB();

        int getA();

        int getNews();
    }

    /** Ile artykułów pliku ma każde oznaczenie TOP i ile jest nowości. */
    @Query(value = """
            select a.file_id as "fileId", count(*) filter (where a.top_mark = 'BTop') as btop,
                   count(*) filter (where a.top_mark = 'B') as b, count(*) filter (where a.top_mark = 'A') as a,
                   count(*) filter (where a.is_new) as news
            from cad_top_articles a where a.file_id in (:fileIds) group by a.file_id
            """, nativeQuery = true)
    List<Counts> countMarks(Collection<UUID> fileIds);

    interface ArticlePrice {
        String getItm8();

        BigDecimal getPurchasePrice();

        BigDecimal getSalePrice();
    }

    /**
     * Ceny z pliku TOP dla artykułów sklepu: po ITM8, potem po EAN-ie z kartoteki i listy cadencier, na końcu po
     * obowiązującym multikodzie (EAN bez zer wiodących od 8 cyfr, krótsze to kody wewnętrzne), a kod wagi po pierwszych
     * 12 cyfrach, bo plik TOP ma cyfrę kontrolną, a kartoteka zwykle zero. Dawnych par z
     * {@code product_eans} celowo nie ma: EAN mógł przejść na inny towar. Z dwóch artykułów TOP o tym samym EAN-ie
     * (zwykły i paletowy) zostaje droższy, czyli zwykły.
     */
    @Query(value = """
            with wanted as (
                select distinct x.itm8 from unnest(cast(:itm8s as varchar[])) as x(itm8)
            ), codes as (
                select p.itm8, p.ean, 1 as priority from wanted w
                join products p on p.store_id = :storeId and p.itm8 = w.itm8
                union all
                select c.itm8, c.ean, 1 from wanted w
                join cadencier_items c on c.store_id = :storeId and c.itm8 = w.itm8
                union all
                select pc.itm8, pc.ean, 2 from wanted w
                join product_codes pc on pc.store_id = :storeId and pc.itm8 = w.itm8 and pc.current
            ), eans as (
                select itm8, ltrim(btrim(ean), '0') as ean, priority from codes where ean is not null
            ), matches as (
                select w.itm8, a.purchase_price, a.sale_price, 0 as priority from wanted w
                join cad_top_articles a on a.file_id = :fileId and a.itm8 = w.itm8
                union all
                select e.itm8, a.purchase_price, a.sale_price, e.priority from eans e
                join cad_top_articles a on a.file_id = :fileId and a.ean = lpad(e.ean, 13, '0')
                where length(e.ean) between 8 and 13
                union all
                select e.itm8, a.purchase_price, a.sale_price, 3 from eans e
                cross join generate_series(0, 9) as check_digit
                join cad_top_articles a on a.file_id = :fileId and a.ean = left(e.ean, 12) || check_digit
                where e.ean ~ '^2[1-9][0-9]{5}00000[0-9]$'
            )
            select distinct on (m.itm8) m.itm8 as itm8, m.purchase_price as "purchasePrice",
                   m.sale_price as "salePrice"
            from matches m order by m.itm8, m.priority, m.purchase_price desc nulls last
            """, nativeQuery = true)
    List<ArticlePrice> findPrices(UUID storeId, UUID fileId, String[] itm8s);
}
