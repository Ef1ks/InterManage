package com.intermanage.backend.sales.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.sales.entity.Sale;

/** Dane raportu ujemnych stanów: artykuły ze stanem poniżej zera, ich sprzedaż i dostawy. */
public interface NegativeStockRepository extends Repository<Sale, Sale.Key> {

    /** Artykuł z ujemnym stanem i sprzedażą z okresu. */
    interface NegativeArticle {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        BigDecimal getStock();

        BigDecimal getPurchasePrice();

        boolean getActive();

        boolean getCadencier();

        BigDecimal getSoldQuantity();

        BigDecimal getSoldGross();

        LocalDate getLastSale();
    }

    /** Artykuły z ujemnym stanem; sprzedaż z {@code salesFrom}..{@code salesTo} i ostatnia sprzedaż w ogóle. */
    @Query(value = """
            with neg as (
                select p.itm8, p.ean, p.name, p.brand, p.subfamily_code, p.stock, p.purchase_price, p.active,
                       exists (select 1 from cadencier_items c
                               where c.store_id = p.store_id and c.itm8 = p.itm8) as cadencier
                from products p
                where p.store_id = :storeId and p.stock < 0 and """ + SaleRepository.IN_CODES + """
            ), sold as (
                select s.itm8, sum(s.quantity) as quantity, sum(s.sale_value_gross) as gross
                from sales_by_article s
                where s.store_id = :storeId and s.sale_date between :salesFrom and :salesTo
                  and s.itm8 in (select itm8 from neg)
                group by s.itm8
            )
            select n.itm8, n.ean, n.name, n.brand, n.subfamily_code, n.stock, n.purchase_price, n.active,
                   n.cadencier, so.quantity as sold_quantity, so.gross as sold_gross,
                   (select max(x.sale_date) from sales x where x.store_id = :storeId and x.itm8 = n.itm8)
                       as last_sale
            from neg n
            left join sold so on so.itm8 = n.itm8
            """, nativeQuery = true)
    List<NegativeArticle> findArticles(UUID storeId, LocalDate salesFrom, LocalDate salesTo, String[] codes);

    /** Dostawy artykułu z ujemnym stanem; dostawca z ostatniego przyjęcia PZ. */
    interface ArticleReceipts {
        String getItm8();

        LocalDateTime getLastReceipt();

        int getDeliveryDays();

        int getPeriodDays();

        String getSupplierCode();

        String getSupplierName();
    }

    /**
     * Przyjęcia PZ i ruchy międzymagazynowe z ilością na minus (towar od innego sklepu) artykułów z ujemnym stanem,
     * także pod lokalnym ITM8 dostawcy (spoza kartoteki), po EAN-ie: głównym, z multikodów i dawnych par. EAN-y bierzemy
     * tylko tych artykułów, bo cała kartoteka z całą historią dokumentów to ok. 7 s, a tak ok. 0,4 s.
     * {@code periodDays}: dni z dostawą od {@code periodFrom} (włącznie) do {@code periodTo} (bez).
     */
    @Query(value = """
            with neg as (
                select itm8 from products where store_id = :storeId and stock < 0
            ), article_codes as (
                select ltrim(btrim(p.ean), '0') as ean, p.itm8
                from products p join neg n on n.itm8 = p.itm8 where p.store_id = :storeId
                union
                select ltrim(btrim(pc.ean), '0'), pc.itm8
                from product_codes pc join neg n on n.itm8 = pc.itm8 where pc.store_id = :storeId and pc.current
                union
                select ltrim(btrim(pe.ean), '0'), p.itm8
                from product_eans pe join products p on p.id = pe.product_id join neg n on n.itm8 = p.itm8
                where p.store_id = :storeId
            ), article_eans as (
                select ean, min(itm8) as itm8 from article_codes where ean <> '' group by ean
            ), lines as (
                select d.type, d.document_date, l.supplier_code, l.supplier_name, l.itm8, l.ean
                from document_lines l
                join documents d on d.id = l.document_id
                where d.store_id = :storeId and d.type in ('PZ', 'MM')
                  and ((d.type = 'PZ' and l.quantity > 0) or (d.type = 'MM' and l.quantity < 0))
            ), received as (
                select coalesce(n.itm8, e.itm8) as itm8, x.type, x.document_date, x.supplier_code, x.supplier_name
                from lines x
                left join neg n on n.itm8 = x.itm8
                left join article_eans e on n.itm8 is null
                    and e.ean = ltrim(btrim(x.ean), '0')
                    and not exists (select 1 from products p where p.store_id = :storeId and p.itm8 = x.itm8)
            )
            select itm8, max(document_date) as last_receipt,
                   count(distinct cast(document_date as date)) as delivery_days,
                   count(distinct cast(document_date as date))
                       filter (where document_date >= :periodFrom and document_date < :periodTo) as period_days,
                   (array_agg(supplier_code order by document_date desc) filter (where type = 'PZ'))[1]
                       as supplier_code,
                   (array_agg(supplier_name order by document_date desc) filter (where type = 'PZ'))[1]
                       as supplier_name
            from received
            where itm8 is not null
            group by itm8
            """, nativeQuery = true)
    List<ArticleReceipts> findReceipts(UUID storeId, LocalDateTime periodFrom, LocalDateTime periodTo);
}
