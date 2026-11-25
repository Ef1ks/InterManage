package com.intermanage.backend.cadtop.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cadtop.entity.CadPriorityRun;

public interface CadPriorityRunRepository extends JpaRepository<CadPriorityRun, UUID> {

    /** Nieusunięte zlecenia dla kart sklepu, od najnowszego. */
    @EntityGraph(attributePaths = "createdBy")
    @Query("""
            select r from CadPriorityRun r
            where r.store.id = :storeId and r.deletedAt is null and r.itm8 in :itm8s order by r.createdAt desc""")
    List<CadPriorityRun> findActive(UUID storeId, Collection<String> itm8s);

    @Query("select r from CadPriorityRun r where r.store.id = :storeId and r.id in :ids")
    List<CadPriorityRun> findInStore(UUID storeId, Collection<UUID> ids);

    List<CadPriorityRun> findByJobIdAndDeletedAtIsNull(UUID jobId);

    /** Karty, których zlecenie ktoś cofnął po tej chwili: automat nie zleca ich od razu z powrotem. */
    @Query("""
            select r.itm8 from CadPriorityRun r
            where r.store.id = :storeId and r.deletedBy is not null and r.deletedAt > :since""")
    Set<String> findWithdrawnSince(UUID storeId, Instant since);

    interface Counts {
        int getTop();

        /** Artykuły TOP, których ITM8 nie jest priorytetowym ITM8 żadnej karty z najnowszego raportu kartoteki. */
        int getMissing();

        /** Priorytetowe, ale nieaktywne. */
        int getInactive();
    }

    @Query(value = """
            select count(*) as top, count(*) filter (where p.id is null) as missing,
                   count(*) filter (where p.active = false) as inactive
            from cad_top_articles t
            left join products p on p.store_id = :storeId and p.itm8 = t.itm8 and p.last_seen_import_id = :importId
            where t.file_id = :fileId
            """, nativeQuery = true)
    Counts count(UUID storeId, UUID fileId, UUID importId);

    interface Match {
        String getTopItm8();

        String getTopEan();

        String getTopName();

        String getTopBrand();

        String getTopMark();

        BigDecimal getTopPurchasePrice();

        BigDecimal getTopSalePrice();

        /** 0 główny EAN karty, 1 multikod, 2 kod wagi bez cyfry kontrolnej. */
        int getRank();

        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        String getSupplier();

        BigDecimal getStock();

        BigDecimal getSalePrice();

        BigDecimal getPurchasePrice();

        Boolean getActive();

        /** ITM8 z TOP był kiedyś kartą w tej kartotece (nie ma go w najnowszym raporcie). */
        boolean getTopKnown();

        /** Karta ma inny ITM8 z tego samego cadencier (np. zwykły zamiast paletowego). */
        boolean getOtherTop();
    }

    /**
     * Karty z najnowszego raportu kartoteki z EAN-em artykułu TOP, którego ITM8 nie jest priorytetowy na żadnej karcie:
     * po głównym EAN-ie, obowiązującym multikodzie i kodzie wagi (pierwsze 12 cyfr, bo plik TOP ma cyfrę kontrolną,
     * a kartoteka zwykle zero). Kody krótsze niż 8 cyfr to kody wewnętrzne.
     */
    @Query(value = """
            with missing as materialized (
                select t.*, ltrim(t.ean, '0') as k from cad_top_articles t
                left join products p on p.store_id = :storeId and p.itm8 = t.itm8
                    and p.last_seen_import_id = :importId
                where t.file_id = :fileId and p.id is null and length(ltrim(t.ean, '0')) >= 8
            ), hits as (
                select t.itm8 as top_itm8, p.id, 0 as rank from missing t
                join products p on p.store_id = :storeId and ltrim(p.ean, '0') = t.k
                union all
                select t.itm8, p.id, 1 from missing t
                join product_codes c on c.store_id = :storeId and c.current and ltrim(c.ean, '0') = t.k
                join products p on p.store_id = c.store_id and p.itm8 = c.itm8
                union all
                select t.itm8, p.id, 2 from missing t
                join products p on p.store_id = :storeId and left(p.ean, 7) = left(t.k, 7)
                    and p.ean ~ '^2[1-9][0-9]{5}00000[0-9]$'
                where t.k ~ '^2[1-9][0-9]{5}00000[0-9]$'
            ), best as (
                select distinct on (h.top_itm8, h.id) h.top_itm8, h.id, h.rank from hits h
                order by h.top_itm8, h.id, h.rank
            )
            select t.itm8 as "topItm8", t.ean as "topEan", t.name as "topName", t.brand as "topBrand",
                   t.top_mark as "topMark", t.purchase_price as "topPurchasePrice", t.sale_price as "topSalePrice",
                   b.rank as rank, p.itm8 as itm8, p.ean as ean, p.name as name, p.brand as brand,
                   p.subfamily_code as "subfamilyCode", p.supplier as supplier, p.stock as stock,
                   p.sale_price as "salePrice", p.purchase_price as "purchasePrice", p.active as active,
                   exists (select 1 from products o where o.store_id = :storeId and o.itm8 = t.itm8) as "topKnown",
                   exists (select 1 from cad_top_articles x where x.file_id = :fileId and x.itm8 = p.itm8)
                       as "otherTop"
            from best b
            join missing t on t.itm8 = b.top_itm8
            join products p on p.id = b.id and p.last_seen_import_id = :importId
            order by p.name, p.itm8, b.rank, t.purchase_price desc nulls last, t.itm8
            """, nativeQuery = true)
    List<Match> findMatches(UUID storeId, UUID fileId, UUID importId);

    /** Karty z priorytetowym ITM8 z cadencier, które według najnowszego raportu kartoteki są nieaktywne. */
    @Query(value = """
            select t.itm8 as "topItm8", t.ean as "topEan", t.name as "topName", t.brand as "topBrand",
                   t.top_mark as "topMark", t.purchase_price as "topPurchasePrice", t.sale_price as "topSalePrice",
                   0 as rank, p.itm8 as itm8, p.ean as ean, p.name as name, p.brand as brand,
                   p.subfamily_code as "subfamilyCode", p.supplier as supplier, p.stock as stock,
                   p.sale_price as "salePrice", p.purchase_price as "purchasePrice", p.active as active,
                   true as "topKnown", false as "otherTop"
            from cad_top_articles t
            join products p on p.store_id = :storeId and p.itm8 = t.itm8 and p.last_seen_import_id = :importId
            where t.file_id = :fileId and p.active = false
            order by p.name, p.itm8
            """, nativeQuery = true)
    List<Match> findInactive(UUID storeId, UUID fileId, UUID importId);
}
