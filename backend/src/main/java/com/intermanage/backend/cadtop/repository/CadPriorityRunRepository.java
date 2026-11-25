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

    interface AssignedElsewhere {
        String getTopItm8();

        String getItm8();

        /** EAN, z którym ITM8 z cadencier był widziany, a który teraz jest na innej karcie. */
        String getEan();

        String getOtherItm8();

        String getOtherName();
    }

    /**
     * ITM8 z cadencier, które w Mercalys są już na innej karcie: ITM8 był widziany (dawna karta, jej pary EAN,
     * multikody, linie dokumentów, pliki dostawy z bazy) z EAN-em spoza karty, do której chcemy go przypiąć, a ten EAN
     * jest dziś na innej karcie najnowszego raportu (główny EAN albo obowiązujący multikod). Mercalys odrzuca wtedy
     * ITM8 komunikatem „Ten kod ITM jest już przyznany”. Kody z etykiet obniżek (13…, 15…, 17…) i wagi pomijane.
     */
    @Query(value = """
            with pairs as (
                select distinct x.top_itm8, x.card_itm8
                from unnest(cast(:tops as varchar[]), cast(:cards as varchar[])) as x(top_itm8, card_itm8)
            ), seen as (
                select x.top_itm8, x.card_itm8, o.ean from pairs x
                join products o on o.store_id = :storeId and o.itm8 = x.top_itm8
                union
                select x.top_itm8, x.card_itm8, pe.ean from pairs x
                join products o on o.store_id = :storeId and o.itm8 = x.top_itm8
                join product_eans pe on pe.product_id = o.id
                union
                select x.top_itm8, x.card_itm8, c.ean from pairs x
                join product_codes c on c.store_id = :storeId and c.itm8 = x.top_itm8
                union
                select x.top_itm8, x.card_itm8, l.ean from pairs x
                join document_lines l on l.itm8 = x.top_itm8
                join documents d on d.id = l.document_id and d.store_id = :storeId
                union
                select x.top_itm8, x.card_itm8, b.ean from pairs x
                join base_delivery_lines b on ltrim(b.itm8, '0') = ltrim(x.top_itm8, '0')
                join base_deliveries d on d.id = b.delivery_id and d.store_id = :storeId
            ), keys as (
                select distinct s.top_itm8, s.card_itm8, ltrim(s.ean, '0') as k from seen s where s.ean is not null
            ), foreign_keys as (
                select k.* from keys k
                join products card on card.store_id = :storeId and card.itm8 = k.card_itm8
                    and card.last_seen_import_id = :importId
                where length(k.k) >= 8 and not (length(k.k) = 13 and k.k ~ '^(1[357]|2[1-9])')
                  and ltrim(card.ean, '0') <> k.k
                  and not exists (select 1 from product_codes c where c.store_id = :storeId and c.itm8 = k.card_itm8
                      and ltrim(c.ean, '0') = k.k)
                  and not exists (select 1 from product_eans pe where pe.product_id = card.id and ltrim(pe.ean, '0') = k.k)
            ), others as (
                select f.top_itm8, f.card_itm8, f.k, p.itm8, p.name from foreign_keys f
                join products p on p.store_id = :storeId and p.last_seen_import_id = :importId
                    and ltrim(p.ean, '0') = f.k
                union
                select f.top_itm8, f.card_itm8, f.k, p.itm8, p.name from foreign_keys f
                join product_codes c on c.store_id = :storeId and c.current and ltrim(c.ean, '0') = f.k
                join products p on p.store_id = c.store_id and p.itm8 = c.itm8 and p.last_seen_import_id = :importId
            )
            select o.top_itm8 as "topItm8", o.card_itm8 as itm8, o.k as ean, o.itm8 as "otherItm8",
                   o.name as "otherName"
            from others o
            where o.itm8 <> o.card_itm8 and o.itm8 <> o.top_itm8
            order by o.top_itm8, o.card_itm8, o.itm8
            """, nativeQuery = true)
    List<AssignedElsewhere> findAssignedElsewhere(UUID storeId, UUID importId, String[] tops, String[] cards);

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
