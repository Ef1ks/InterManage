package com.intermanage.backend.sales.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.repository.ArticleMergeRepository;
import com.intermanage.backend.sales.entity.Sale;

/** Dane artykułów dodawanych do koszyka zakupowego z raportów. */
public interface BasketRepository extends Repository<Sale, Sale.Key> {

    interface Article {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        BigDecimal getStock();

        BigDecimal getCaseSize();

        BigDecimal getPurchasePrice();

        boolean getCadencier();
    }

    @Query(value = """
            select p.itm8, p.ean, p.name, p.brand, p.subfamily_code, p.stock, p.case_size, p.purchase_price,
                   exists (select 1 from cadencier_items c
                           where c.store_id = p.store_id and c.itm8 = p.itm8) as cadencier
            from products p
            where p.store_id = :storeId and p.itm8 = any (cast(:itm8s as varchar[]))
            """, nativeQuery = true)
    List<Article> findArticles(UUID storeId, String[] itm8s);

    interface ArticleSupplier {
        String getItm8();

        String getSupplierCode();

        String getSupplierName();

        BigDecimal getPurchasePrice();

        BigDecimal getLastQuantity();

        LocalDateTime getLastDate();

        int getDeliveries();

        int getRank();
    }

    /**
     * Dostawcy artykułów z przyjęć PZ od {@code since}, także pod lokalnym ITM8 dostawcy po EAN-ie. Cena i ilość z
     * ostatniej dostawy od dostawcy, po korektach przypiętych do przyjęcia (korekta, która zeruje ilość, zostawia liczby
     * z bonu). Kod dostawcy bez zer wiodących, od najświeższej dostawy. Linie i korekty są {@code materialized}, a EAN-y
     * tylko wybranych artykułów: z całą kartoteką planista wybierał zagnieżdżone pętle (28 s zamiast ułamka sekundy).
     */
    @Query(value = "with " + ArticleMergeRepository.MERGED + """
            , members as materialized (
                select x.itm8 as article, x.itm8
                from unnest(cast(:itm8s as varchar[])) as x(itm8)
                union
                select m.main_itm8, m.alias_itm8 from merged m where m.main_itm8 = any (cast(:itm8s as varchar[]))
            ), article_codes as materialized (
                select ltrim(btrim(p.ean), '0') as ean, m.article
                from members m join products p on p.store_id = :storeId and p.itm8 = m.itm8
                union
                select ltrim(btrim(pc.ean), '0'), m.article
                from members m join product_codes pc on pc.store_id = :storeId and pc.itm8 = m.itm8 and pc.current
                union
                select ltrim(btrim(pe.ean), '0'), m.article
                from members m
                join products p on p.store_id = :storeId and p.itm8 = m.itm8
                join product_eans pe on pe.product_id = p.id
            ), article_lines as materialized (
                select m.article, l.id from members m join document_lines l on l.itm8 = m.itm8
                union
                select e.article, l.id
                from (select ean, min(article) as article from article_codes where ean <> '' group by ean) e
                join document_lines l on ltrim(btrim(l.ean), '0') = e.ean
                where not exists (select 1 from products o where o.store_id = :storeId and o.itm8 = l.itm8)
                  and not exists (select 1 from members m where m.itm8 = l.itm8)
            ), lines as materialized (
                select d.id as document_id, d.number, d.document_date, l.itm8, a.article,
                       coalesce(ltrim(btrim(l.supplier_code), '0'), '') as supplier_code,
                       max(btrim(l.supplier_name)) as supplier_name,
                       sum(l.quantity) as quantity, coalesce(sum(l.purchase_value), 0) as purchase_value
                from article_lines a
                join document_lines l on l.id = a.id
                join documents d on d.id = l.document_id and d.error_marked_at is null
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and d.document_date >= :since
                group by 1, 2, 3, 4, 5, 6
            ), fixes as materialized (
                select c.delivery_id, cl.itm8, sum(cl.quantity) as quantity, sum(cl.purchase_value) as purchase_value
                from delivery_corrections c
                join document_lines cl on cl.document_id = c.correction_id
                where c.unlinked_at is null and c.delivery_id in (select document_id from lines)
                group by c.delivery_id, cl.itm8
            )
            , priced as (
                select l.article, l.supplier_code, l.supplier_name, l.document_id, l.number, l.document_date,
                       case when l.quantity + coalesce(f.quantity, 0) > 0
                            then l.quantity + coalesce(f.quantity, 0) else l.quantity end as quantity,
                       case when l.quantity + coalesce(f.quantity, 0) > 0
                            then l.purchase_value + coalesce(f.purchase_value, 0)
                            else l.purchase_value end as purchase_value
                from lines l
                left join fixes f on f.delivery_id = l.document_id and f.itm8 = l.itm8
            ), delivered as (
                select article, supplier_code, max(supplier_name) as supplier_name, document_id, number, document_date,
                       sum(quantity) as quantity, sum(purchase_value) as purchase_value,
                       row_number() over (partition by article order by document_date desc, number desc) as rank
                from priced
                group by article, supplier_code, document_id, number, document_date
            )
            select article as itm8, supplier_code,
                   (array_agg(supplier_name order by document_date desc, number desc))[1] as supplier_name,
                   (array_agg(round(purchase_value / quantity, 4) order by document_date desc, number desc))[1]
                       as purchase_price,
                   (array_agg(quantity order by document_date desc, number desc))[1] as last_quantity,
                   max(document_date) as last_date, count(*) as deliveries,
                   min(rank) as rank
            from delivered
            where supplier_code <> '' or coalesce(supplier_name, '') <> ''
            group by article, supplier_code, case when supplier_code = '' then supplier_name end
            order by article, max(document_date) desc
            """, nativeQuery = true)
    List<ArticleSupplier> findSuppliers(UUID storeId, LocalDateTime since, String[] itm8s);
}
