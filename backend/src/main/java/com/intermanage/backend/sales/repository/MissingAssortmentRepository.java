package com.intermanage.backend.sales.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.sales.entity.Sale;

/** Dane raportu brakującego asortymentu: zakupy spoza bazy, dostawy i sprzedaż dzienna artykułów. */
public interface MissingAssortmentRepository extends Repository<Sale, Sale.Key> {

    /** Artykuł kupiony w okresie od dostawcy spoza bazy, którego dziś nie ma na stanie albo jest go mało. */
    interface BoughtArticle {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        BigDecimal getStock();

        BigDecimal getSalePrice();

        boolean getCadencier();

        int getPurchases();

        LocalDateTime getLastPurchase();

        String getSupplierCode();

        String getSupplierName();

        BigDecimal getLastQuantity();

        BigDecimal getLastValue();

        LocalDate getLastSale();
    }

    /**
     * Artykuły z przyjęć PZ od {@code since} od dostawców spoza {@code baseCodes} (linia z lokalnym ITM8 dostawcy
     * należy do artykułu po EAN-ie), ze stanem najwyżej {@code maxStock} (0 = brak). Dostawca, ilość i wartość z ostatniego takiego przyjęcia,
     * ostatnia sprzedaż w ogóle (pusta = artykuł nigdy się nie sprzedał).
     */
    @Query(value = DocumentLineRepository.STORE_ARTICLE_EANS + """
            , bought as (
                select coalesce(mm.main_itm8, p.itm8, e.itm8) as itm8, d.id as document_id, d.document_date, l.supplier_code,
                       l.supplier_name, l.quantity, l.purchase_value
                from document_lines l
                join documents d on d.id = l.document_id and d.error_marked_at is null
                left join products p on p.store_id = :storeId and p.itm8 = l.itm8
                left join merged mm on mm.alias_itm8 = l.itm8
                left join article_eans e on p.id is null and e.ean = ltrim(btrim(l.ean), '0')
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and d.document_date >= :since
                  and coalesce(ltrim(btrim(l.supplier_code), '0'), '') <> all (cast(:baseCodes as varchar[]))
            ), last_bought as (
                select itm8, count(distinct document_id) as purchases, max(document_date) as last_purchase,
                       (array_agg(supplier_code order by document_date desc))[1] as supplier_code,
                       (array_agg(supplier_name order by document_date desc))[1] as supplier_name,
                       (array_agg(quantity order by document_date desc))[1] as last_quantity,
                       (array_agg(purchase_value order by document_date desc))[1] as last_value
                from bought
                where itm8 is not null
                group by itm8
            )
            select p.itm8, p.ean, p.name, p.brand, p.subfamily_code, p.stock, p.sale_price, b.purchases,
                   b.last_purchase, b.supplier_code, b.supplier_name, b.last_quantity, b.last_value,
                   (select max(s.sale_date) from sales s
                    where s.store_id = :storeId and s.itm8 = p.itm8 and s.quantity > 0) as last_sale,
                   exists (select 1 from cadencier_items c
                           where c.store_id = p.store_id and c.itm8 = p.itm8) as cadencier
            from last_bought b
            join products p on p.store_id = :storeId and p.itm8 = b.itm8
            where coalesce(p.stock, 0) <= :maxStock and """ + SaleRepository.IN_CODES, nativeQuery = true)
    List<BoughtArticle> findBoughtWithStockUpTo(UUID storeId, LocalDateTime since, String[] baseCodes, String[] codes,
            BigDecimal maxStock);

    /** Dzień, w którym do artykułu przyszedł towar. */
    interface DeliveryDay {
        String getItm8();

        LocalDate getDay();
    }

    /**
     * Dni z dostawą artykułów {@code itm8s} od {@code since}: przyjęcia PZ od każdego dostawcy (także z bazy) i ruchy
     * międzymagazynowe z ilością na minus (towar od innego sklepu), także pod lokalnym ITM8 po EAN-ie.
     */
    @Query(value = DocumentLineRepository.STORE_ARTICLE_EANS + """
            select distinct coalesce(mm.main_itm8, p.itm8, e.itm8) as itm8, cast(d.document_date as date) as day
            from document_lines l
            join documents d on d.id = l.document_id and d.error_marked_at is null
            left join products p on p.store_id = :storeId and p.itm8 = l.itm8
            left join merged mm on mm.alias_itm8 = l.itm8
            left join article_eans e on p.id is null and e.ean = ltrim(btrim(l.ean), '0')
            where d.store_id = :storeId and d.document_date >= :since
              and ((d.type = 'PZ' and l.quantity > 0) or (d.type = 'MM' and l.quantity < 0))
              and coalesce(mm.main_itm8, p.itm8, e.itm8) = any (cast(:itm8s as varchar[]))
            """, nativeQuery = true)
    List<DeliveryDay> findDeliveryDays(UUID storeId, LocalDateTime since, String[] itm8s);

    /** Sprzedaż dnia artykułu (suma po EAN-ach). */
    interface DaySale {
        String getItm8();

        LocalDate getDay();

        BigDecimal getQuantity();
    }

    @Query(value = """
            select itm8, sale_date as day, quantity
            from sales_by_article
            where store_id = :storeId and sale_date between :from and :to and quantity > 0
              and itm8 = any (cast(:itm8s as varchar[]))
            """, nativeQuery = true)
    List<DaySale> findDailySales(UUID storeId, LocalDate from, LocalDate to, String[] itm8s);
}
