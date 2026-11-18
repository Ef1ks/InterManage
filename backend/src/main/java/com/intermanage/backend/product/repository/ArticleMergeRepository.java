package com.intermanage.backend.product.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ArticleMerge;

/**
 * Połączenia artykułów w sklepie (patrz {@link ArticleMerge}). Reguła: artykuł spoza najnowszego raportu kartoteki,
 * którego EAN (bieżący albo z dawnych par) jest teraz obowiązującym multikodem albo głównym EAN-em jednego artykułu
 * z najnowszego raportu, należy do niego. EAN prowadzący do kilku artykułów nie łączy niczego. Te same zapytania co
 * w V165.
 */
public interface ArticleMergeRepository extends JpaRepository<ArticleMerge, UUID> {

    /**
     * ITM8 artykułu {@code :itm8} sklepu {@code :storeId} razem z połączonymi z nim artykułami usuniętymi, do
     * warunku {@code itm8 in (…)} w zapytaniach natywnych karty artykułu.
     */
    String ARTICLE_ITM8S = """
            (select cast(:itm8 as varchar) union select am.alias_itm8 from article_merges am
             where am.store_id = :storeId and am.main_itm8 = :itm8 and am.ended_at is null)""";

    /**
     * Połączenia obowiązujące sklepu {@code :storeId} jako CTE {@code merged} (alias_itm8 → main_itm8), do raportów,
     * które przypisują linie dokumentów artykułom.
     */
    String MERGED = """
            merged as (select am.alias_itm8, am.main_itm8 from article_merges am
                       where am.store_id = :storeId and am.ended_at is null)""";

    String PAIRS = """
            with latest as (
                select i.id from product_imports i where i.store_id = :storeId
                order by i.report_generated_at desc limit 1
            ), gone as (
                select p.itm8, ltrim(btrim(e.ean), '0') as ean
                from products p
                cross join lateral (select p.ean union select pe.ean from product_eans pe where pe.product_id = p.id) e
                where p.store_id = :storeId and p.last_seen_import_id <> (select id from latest)
                  and ltrim(btrim(e.ean), '0') <> ''
            ), owners as (
                select c.itm8, ltrim(btrim(c.ean), '0') as ean from product_codes c
                where c.store_id = :storeId and c.current
                union
                select p.itm8, ltrim(btrim(p.ean), '0') from products p
                where p.store_id = :storeId and p.last_seen_import_id = (select id from latest)
            ), found as (
                select g.itm8 as alias_itm8, o.itm8 as main_itm8, min(g.ean) as ean
                from gone g
                join owners o on o.ean = g.ean and o.itm8 <> g.itm8
                join products m on m.store_id = :storeId and m.itm8 = o.itm8
                                and m.last_seen_import_id = (select id from latest)
                group by g.itm8, o.itm8
            ), pairs as (
                select f.* from found f
                where not exists (select 1 from found x where x.alias_itm8 = f.alias_itm8 and x.main_itm8 <> f.main_itm8)
            )
            """;

    /**
     * Statystyki tabel kartoteki przed {@link #refresh} w transakcji importu. Bez nich planista nie zna wierszy dopisanych
     * w tej transakcji (nowy sklep: kartoteka „pusta”) i łączy {@code gone} z {@code owners} pętlą zagnieżdżoną: przy
     * 20 tys. artykułów ok. 80 s zamiast ułamka sekundy. ANALYZE liczy też wiersze własnej, niezatwierdzonej transakcji.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "analyze products, product_eans, product_codes", nativeQuery = true)
    void analyze();

    /** Kończy połączenia sklepu, które przestały wynikać z kartoteki i multikodów. */
    @Modifying(flushAutomatically = true)
    @Query(value = PAIRS + """
            update article_merges m set ended_at = now()
            where m.store_id = :storeId and m.ended_at is null
              and not exists (select 1 from pairs p where p.alias_itm8 = m.alias_itm8 and p.main_itm8 = m.main_itm8)
            """, nativeQuery = true)
    int endStale(UUID storeId);

    /** Zapisuje nowe połączenia sklepu (po {@link #endStale}). */
    @Modifying(flushAutomatically = true)
    @Query(value = PAIRS + """
            insert into article_merges (id, store_id, alias_itm8, main_itm8, ean, created_at)
            select gen_random_uuid(), :storeId, p.alias_itm8, p.main_itm8, p.ean, now()
            from pairs p
            where not exists (select 1 from article_merges m
                              where m.store_id = :storeId and m.ended_at is null and m.alias_itm8 = p.alias_itm8)
            """, nativeQuery = true)
    int insertNew(UUID storeId);

    /** Połączenia obowiązujące artykułu głównego. */
    @Query("""
            select m from ArticleMerge m
            where m.storeId = :storeId and m.mainItm8 = :itm8 and m.endedAt is null
            order by m.aliasItm8
            """)
    List<ArticleMerge> findAliases(UUID storeId, String itm8);

    /** Artykuł, z którym połączono {@code itm8}, gdy to artykuł usunięty. */
    @Query("""
            select m from ArticleMerge m
            where m.storeId = :storeId and m.aliasItm8 = :itm8 and m.endedAt is null
            """)
    Optional<ArticleMerge> findMain(UUID storeId, String itm8);

    /** Odświeża połączenia sklepu po imporcie kartoteki albo multikodów (w transakcji importu). */
    default void refresh(UUID storeId) {
        endStale(storeId);
        insertNew(storeId);
    }
}
