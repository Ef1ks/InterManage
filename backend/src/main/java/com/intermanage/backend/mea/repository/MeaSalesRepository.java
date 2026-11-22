package com.intermanage.backend.mea.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.mea.entity.MeaDocument;

/** Sprzedaż i promocje artykułów dokumentu MEA (tygodnie czwartek-środa pod pozycjami). */
public interface MeaSalesRepository extends JpaRepository<MeaDocument, UUID> {

    @Query(value = "select max(sale_date) from sales where store_id = :storeId", nativeQuery = true)
    LocalDate findLastSaleDate(UUID storeId);

    interface DayRow {
        String getItm8();

        LocalDate getSaleDate();

        BigDecimal getQuantity();

        BigDecimal getGross();

        boolean getPriceDrop();
    }

    /**
     * Sprzedaż dzienna artykułów z rozpoznaniem promocji ze sprzedaży: cena dnia (brutto / ilość) poniżej
     * {@code priceFactor} × cena zwykła, czyli najwyższy 75. percentyl cen dnia z miesiąca i dwóch poprzednich
     * (miesiąc z co najmniej {@code minReferenceDays} dniami sprzedaży), jak w
     * {@link com.intermanage.backend.promotion.repository.PromotionReportRepository#PROMO_FLAGS}.
     */
    @Query(value = """
            with days as (
                select s.itm8, s.sale_date, s.quantity, s.sale_value_gross as gross,
                       case when s.quantity > 0 and s.sale_value_gross > 0
                            then s.sale_value_gross / s.quantity end as price,
                       cast(date_trunc('month', s.sale_date) as date) as month
                from sales_by_article s
                where s.store_id = :storeId and s.itm8 in (:itm8s)
                  and s.sale_date between cast(:dataFrom as date) and cast(:dataTo as date)
            ), reference as (
                select itm8, month,
                       max(price) filter (where sale_days >= :minReferenceDays)
                           over (partition by itm8 order by month
                                 range between interval '2 month' preceding and current row) as price
                from sales_month_prices
                where store_id = :storeId and itm8 in (:itm8s)
                  and month between cast(:referenceFrom as date) and cast(:dataTo as date)
            )
            select d.itm8 as itm8, d.sale_date as saleDate, d.quantity as quantity, d.gross as gross,
                   coalesce(d.price < r.price * :priceFactor, false) as priceDrop
            from days d
            left join reference r on r.itm8 = d.itm8 and r.month = d.month
            order by d.itm8, d.sale_date
            """, nativeQuery = true)
    List<DayRow> findDays(UUID storeId, Collection<String> itm8s, LocalDate dataFrom, LocalDate dataTo,
            LocalDate referenceFrom, BigDecimal priceFactor, int minReferenceDays);

    interface PeriodRow {
        String getItm8();

        LocalDate getDateFrom();

        LocalDate getDateTo();

        String getSource();
    }

    /** Promocje z listy promocji (rzeczywisty koniec) i z kasy nachodzące na okres. */
    @Query(value = """
            select itm8, start_date as dateFrom, effective_end as dateTo, 'LIST' as source
            from promotions
            where store_id = :storeId and itm8 in (:itm8s)
              and effective_end >= cast(:dataFrom as date) and start_date <= cast(:dataTo as date)
            union
            select itm8, promo_from, promo_to, 'TILL'
            from till_promotions
            where store_id = :storeId and itm8 in (:itm8s)
              and promo_to >= cast(:dataFrom as date) and promo_from <= cast(:dataTo as date)
            order by 1, 2
            """, nativeQuery = true)
    List<PeriodRow> findPromotions(UUID storeId, Collection<String> itm8s, LocalDate dataFrom, LocalDate dataTo);
}
