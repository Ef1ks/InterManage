package com.intermanage.backend.sales.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.sales.entity.Sale;

/** Dane raportu braków towaru: typowa sprzedaż artykułów i to, co do nich przyjechało. */
public interface StockGapRepository extends Repository<Sale, Sale.Key> {

    /** Ostatni dzień z wgraną sprzedażą: od niego liczymy dni bez sprzedaży, bo sprzedaż sięga najwyżej do wczoraj. */
    @Query(value = "select max(sale_date) from sales where store_id = :storeId", nativeQuery = true)
    Optional<LocalDate> findLastSaleDate(UUID storeId);

    /** Artykuł z typową sprzedażą z ostatnich 8 tygodni, w których był w sprzedaży. */
    interface SellingArticle {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        BigDecimal getStock();

        boolean getCadencier();

        LocalDate getLastSale();

        int getWeeksSold();

        BigDecimal getTypicalWeek();

        BigDecimal getSoldQuantity();

        BigDecimal getMarginValue();
    }

    /**
     * Aktywne artykuły, które w 8 tygodniach przed swoją ostatnią sprzedażą sprzedawały się co najmniej przez
     * {@code minWeeks} tygodni, z medianą tych tygodni od {@code minWeekly}. Tygodnie liczymy od ostatniej sprzedaży,
     * a nie od dziś, i pomijamy te bez sprzedaży: gdy towaru nie było, sprzedaż 0 nie mówi nic o popycie. Mediana, bo
     * średnią zawyża jednorazowy skok z gazetki. Artykuł bez sprzedaży od ponad 8 tygodni tu nie trafia.
     */
    @Query(value = """
            with sold as (
                select itm8, sale_date, quantity, margin_value
                from sales_by_article
                where store_id = :storeId and sale_date between cast(:to as date) - 111 and :to and quantity > 0
            ), last_sold as (
                select itm8, max(sale_date) as last_sale
                from sold
                group by itm8
                having max(sale_date) > cast(:to as date) - 56
            ), weekly as (
                select s.itm8, (l.last_sale - s.sale_date) / 7 as week, sum(s.quantity) as quantity,
                       sum(s.margin_value) as margin_value
                from sold s
                join last_sold l on l.itm8 = s.itm8
                where s.sale_date > l.last_sale - 56
                group by 1, 2
            ), typical as (
                select itm8, count(*) as weeks_sold,
                       percentile_cont(0.5) within group (order by cast(quantity as double precision)) as typical_week,
                       sum(quantity) as sold_quantity, sum(margin_value) as margin_value
                from weekly
                group by itm8
                having count(*) >= :minWeeks
            )
            select p.itm8, p.ean, p.name, p.brand, p.subfamily_code, p.stock, l.last_sale, t.weeks_sold,
                   cast(t.typical_week as numeric(14, 3)) as typical_week, t.sold_quantity, t.margin_value,
                   exists (select 1 from cadencier_items c
                           where c.store_id = p.store_id and c.itm8 = p.itm8) as cadencier
            from typical t
            join last_sold l on l.itm8 = t.itm8
            join products p on p.store_id = :storeId and p.itm8 = t.itm8
            where p.active and t.typical_week >= :minWeekly and """ + SaleRepository.IN_CODES,
            nativeQuery = true)
    List<SellingArticle> findSellingArticles(UUID storeId, LocalDate to, int minWeeks, double minWeekly,
            String[] codes);

    /** Przyjęcia artykułu; dostawca z ostatniego przyjęcia PZ. */
    interface ArticleReceipts {
        String getItm8();

        LocalDateTime getLastReceipt();

        int getRecentDays();

        String getSupplierCode();

        String getSupplierName();
    }

    /**
     * Przyjęcia PZ i ruchy międzymagazynowe z ilością na minus (towar od innego sklepu), także pod lokalnym ITM8
     * dostawcy, po EAN-ie. {@code recentDays} to liczba dni z przyjęciem od {@code regularSince}: mówi, czy towar
     * przyjeżdża regularnie, czy był jednorazowo.
     */
    @Query(value = DocumentLineRepository.STORE_ARTICLE_EANS + """
            , received as (
                select coalesce(p.itm8, e.itm8) as itm8, d.type, d.document_date, l.supplier_code, l.supplier_name
                from document_lines l
                join documents d on d.id = l.document_id
                left join products p on p.store_id = :storeId and p.itm8 = l.itm8
                left join article_eans e on p.id is null and e.ean = ltrim(btrim(l.ean), '0')
                where d.store_id = :storeId
                  and ((d.type = 'PZ' and l.quantity > 0) or (d.type = 'MM' and l.quantity < 0))
            )
            select itm8, max(document_date) as last_receipt,
                   count(distinct cast(document_date as date)) filter (where document_date >= :regularSince)
                       as recent_days,
                   (array_agg(supplier_code order by document_date desc) filter (where type = 'PZ'))[1]
                       as supplier_code,
                   (array_agg(supplier_name order by document_date desc) filter (where type = 'PZ'))[1]
                       as supplier_name
            from received
            where itm8 is not null
            group by itm8
            """, nativeQuery = true)
    List<ArticleReceipts> findReceipts(UUID storeId, LocalDateTime regularSince);
}
