package com.intermanage.backend.promotion.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.leaflet.repository.PromoLeafletRepository;
import com.intermanage.backend.promotion.entity.PromotionImport;

/**
 * Raport skuteczności promocji sklepu: promocje z listy promocji i rozpoznane ze sprzedaży, każda ze sprzedażą
 * w trakcie, w 28 dniach przed (baza) i 14 dniach po. Promocje gazetkowe nie są promocjami sklepu i do niego nie wchodzą.
 */
public interface PromotionReportRepository extends JpaRepository<PromotionImport, UUID> {

    /** Sprzedaż dzienna artykułów do rozpoznania promocji, zawartość CTE {@code days} (patrz {@link #PROMO_FLAGS}). */
    String SALE_DAYS = """
            select s.itm8, s.sale_date, s.quantity, s.sale_value_gross as gross, s.sale_value_net as net,
                   s.sale_value_net - s.cost_value as margin,
                   case when s.quantity > 0 and s.sale_value_gross > 0
                        then s.sale_value_gross / s.quantity end as price,
                   cast(date_trunc('month', s.sale_date) as date) as month
            from sales_by_article s
            where s.store_id = :storeId
              and s.sale_date between cast(:dataFrom as date) and cast(:dataTo as date)
            """;

    /** Cena zwykła artykułu w miesiącu, CTE {@code reference} (patrz {@link #findPromotions}). */
    String REGULAR_PRICES = """
            reference as (
                select itm8, month,
                       max(price) filter (where sale_days >= :minReferenceDays)
                           over (partition by itm8 order by month
                                 range between interval '2 month' preceding and current row) as price
                from sales_month_prices
                where store_id = :storeId and month between cast(:referenceFrom as date) and cast(:dataTo as date)
            )
            """;

    /**
     * Rozpoznanie promocji ze sprzedaży {@code days}: {@code flagged} to dni z ceną poniżej {@code priceFactor} × cena
     * zwykła (patrz {@link #findPromotions}).
     */
    String PROMO_FLAGS = REGULAR_PRICES + """
            , flagged as (
                select d.itm8, d.sale_date, r.price as reference_price
                from days d
                join reference r on r.itm8 = d.itm8 and r.month = d.month
                where d.price < r.price * :priceFactor
            )
            """;

    /**
     * Artykuły, które w dniach {@code dataFrom}-{@code dataTo} były w promocji (z listy, z gazetki albo rozpoznanej ze
     * sprzedaży) i na których w sumie z tych dni marża wyniosła najwyżej {@code maxMargin} sprzedaży netto.
     */
    @Query(value = "with leaflet as (" + PromoLeafletRepository.ARTICLE_PERIODS + "), days as (" + SALE_DAYS + "), "
            + PROMO_FLAGS + """
            , promo_days as (
                select itm8, sale_date from flagged
                where sale_date between cast(:dataFrom as date) and cast(:dataTo as date)
                union
                select p.itm8, cast(g as date)
                from promotions p
                cross join lateral generate_series(greatest(p.start_date, cast(:dataFrom as date)),
                                                   least(p.end_date, cast(:dataTo as date)), interval '1 day') g
                where p.store_id = :storeId
                  and p.end_date >= cast(:dataFrom as date) and p.start_date <= cast(:dataTo as date)
                union
                select l.itm8, cast(g as date)
                from leaflet l
                cross join lateral generate_series(greatest(l.date_from, cast(:dataFrom as date)),
                                                   least(l.date_to, cast(:dataTo as date)), interval '1 day') g
            )
            select d.itm8
            from promo_days x
            join days d on d.itm8 = x.itm8 and d.sale_date = x.sale_date
            group by d.itm8
            having sum(d.net) > 0 and sum(d.margin) <= :maxMargin * sum(d.net)
            """, nativeQuery = true)
    List<String> findLowMarginPromotionArticles(UUID storeId, LocalDate dataFrom, LocalDate dataTo,
            LocalDate referenceFrom, BigDecimal priceFactor, int minReferenceDays, BigDecimal maxMargin);

    /** Najwcześniejszy początek promocji z listy, która kończy się w okresie (baza liczy się sprzed niego). */
    @Query(value = """
            select min(start_date) from promotions
            where store_id = :storeId and end_date between :from and :to
            """, nativeQuery = true)
    LocalDate findEarliestStart(UUID storeId, LocalDate from, LocalDate to);

    /** Od kiedy sklep wgrywa listę promocji; wcześniejsze promocje raport zna tylko ze sprzedaży. */
    @Query(value = "select cast(min(imported_at) as date) from promotion_imports where store_id = :storeId",
            nativeQuery = true)
    LocalDate findFirstImportDate(UUID storeId);

    /** Promocja z sumami sprzedaży: w trakcie, w bazie (dni bez innych promocji) i po promocji. */
    interface Row {
        boolean getListed();

        String getItm8();

        LocalDate getStartDate();

        LocalDate getEndDate();

        BigDecimal getRegularPrice();

        BigDecimal getPromoPrice();

        BigDecimal getListMarginPercent();

        long getPromoDays();

        BigDecimal getPromoQuantity();

        BigDecimal getPromoGross();

        BigDecimal getPromoNet();

        BigDecimal getPromoMargin();

        long getBaseDays();

        BigDecimal getBaseQuantity();

        BigDecimal getBaseGross();

        BigDecimal getBaseNet();

        BigDecimal getBaseMargin();

        long getAfterDays();

        BigDecimal getAfterQuantity();

        BigDecimal getPromoLosses();

        BigDecimal getBaseLosses();

        String getName();

        String getBrand();

        String getEan();

        String getRegionCode();

        String getRegionName();
    }

    /**
     * Promocje kończące się w okresie {@code from}-{@code to}: z listy promocji i rozpoznane ze sprzedaży.
     * <p>
     * <b>Rozpoznanie:</b> cena dnia = sprzedaż brutto / ilość. Cena zwykła artykułu w miesiącu to najwyższy
     * 75. percentyl cen dnia z tego i dwóch poprzednich miesięcy (miesiąc z co najmniej {@code minReferenceDays}
     * dniami sprzedaży), więc promocja trwająca cały miesiąc nie staje się ceną zwykłą. Dzień z ceną poniżej
     * {@code priceFactor} × cena zwykła jest promocyjny; takie dni z przerwami do {@code gapDays} dni to jedna
     * promocja, o ile ma co najmniej {@code minDays} dni promocyjnych i trwa najwyżej {@code maxDays} (dłuższy spadek
     * to zwykle sezonowa zmiana ceny, np. warzyw). Promocja kończąca się w ostatnich 3 dniach sprzedaży może trwać
     * dalej i jest pomijana, a nachodząca na promocję z listy ustępuje jej (lista zna dokładne daty).
     * <p>
     * <b>Baza</b> to {@code baseDays} dni przed promocją, <b>po</b> to {@code afterDays} dni po niej (najwyżej do
     * ostatniego dnia sprzedaży); z obu wypadają dni promocyjne tego artykułu (rozpoznane i z listy). Dni bez
     * sprzedaży liczą się jako zero. Straty to likwidacje (PL) w wartości zakupu netto: w trakcie promocji i po niej
     * oraz w bazie. {@code dataFrom}-{@code dataTo} to dni, które obejmują wszystkie okna, {@code referenceFrom}
     * sięga jeszcze dwa miesiące wcześniej dla ceny zwykłej.
     * <p>
     * <b>Gazetki:</b> promocja artykułu nachodząca na jego promocję gazetkową
     * ({@link PromoLeafletRepository#ARTICLE_PERIODS}) wypada z raportu, a dni gazetki nie liczą się do bazy ani do
     * sprzedaży po promocji.
     */
    @Query(value = "with leaflet as (" + PromoLeafletRepository.ARTICLE_PERIODS + "), days as (" + SALE_DAYS + "), "
            + PROMO_FLAGS + """
            , runs as (
                select f.*, sum(case when f.sale_date - f.previous <= :gapDays then 0 else 1 end)
                                over (partition by f.itm8 order by f.sale_date) as run
                from (select flagged.*, lag(sale_date) over (partition by itm8 order by sale_date) as previous
                      from flagged) f
            ), estimated as (
                select itm8, min(sale_date) as start_date, max(sale_date) as end_date,
                       avg(reference_price) as reference_price
                from runs
                group by itm8, run
                having count(*) >= :minDays
            ), candidates as (
                select true as listed, p.itm8, p.start_date, p.end_date, p.regular_sale_price as regular_price,
                       p.promo_price, p.margin_percent as list_margin_percent
                from promotions p
                where p.store_id = :storeId and p.end_date between cast(:from as date) and cast(:to as date)
                union all
                select false, e.itm8, e.start_date, e.end_date, round(cast(e.reference_price as numeric), 2),
                       null, null
                from estimated e
                where e.end_date between cast(:from as date) and cast(:to as date)
                  and e.end_date <= cast(:lastDay as date) - 3
                  and e.end_date - e.start_date < :maxDays
                  and not exists (select 1 from promotions p
                                  where p.store_id = :storeId and p.itm8 = e.itm8
                                    and p.start_date <= e.end_date and p.end_date >= e.start_date)
            ), promos as (
                select c.* from candidates c
                where not exists (select 1 from leaflet g
                                  where g.itm8 = c.itm8 and g.date_from <= c.end_date and g.date_to >= c.start_date)
            ), excluded as (
                select itm8, sale_date as day from flagged
                union
                select p.itm8, cast(g as date)
                from promotions p
                cross join lateral generate_series(p.start_date, p.end_date, interval '1 day') g
                where p.store_id = :storeId
                  and p.end_date >= cast(:dataFrom as date) and p.start_date <= cast(:dataTo as date)
                union
                select l.itm8, cast(g as date)
                from leaflet l
                cross join lateral generate_series(greatest(l.date_from, cast(:dataFrom as date)),
                                                   least(l.date_to, cast(:dataTo as date)), interval '1 day') g
            ), windows as (
                select pm.itm8, pm.start_date, pm.end_date, w.part, cast(g as date) as day
                from promos pm
                cross join lateral (values ('PROMO', pm.start_date, pm.end_date),
                                           ('BASE', pm.start_date - :baseDays, pm.start_date - 1),
                                           ('AFTER', pm.end_date + 1,
                                            least(pm.end_date + :afterDays, cast(:lastDay as date))))
                    w(part, first_day, last_day)
                cross join lateral generate_series(w.first_day, w.last_day, interval '1 day') g
                left join excluded x on x.itm8 = pm.itm8 and x.day = cast(g as date)
                where w.part = 'PROMO' or x.itm8 is null
            ), losses as (
                select l.itm8, cast(d.document_date as date) as day, -sum(l.purchase_value) as value
                from document_lines l
                join documents d on d.id = l.document_id and d.error_marked_at is null
                where d.store_id = :storeId and d.type = 'PL'
                  and d.document_date >= cast(:dataFrom as timestamp)
                  and d.document_date < cast(:dataTo as timestamp) + interval '1 day'
                group by 1, 2
            ), sums as (
                select w.itm8, w.start_date, w.end_date,
                       count(*) filter (where w.part = 'PROMO') as promo_days,
                       coalesce(sum(s.quantity) filter (where w.part = 'PROMO'), 0) as promo_quantity,
                       coalesce(sum(s.gross) filter (where w.part = 'PROMO'), 0) as promo_gross,
                       coalesce(sum(s.net) filter (where w.part = 'PROMO'), 0) as promo_net,
                       coalesce(sum(s.margin) filter (where w.part = 'PROMO'), 0) as promo_margin,
                       count(*) filter (where w.part = 'BASE') as base_days,
                       coalesce(sum(s.quantity) filter (where w.part = 'BASE'), 0) as base_quantity,
                       coalesce(sum(s.gross) filter (where w.part = 'BASE'), 0) as base_gross,
                       coalesce(sum(s.net) filter (where w.part = 'BASE'), 0) as base_net,
                       coalesce(sum(s.margin) filter (where w.part = 'BASE'), 0) as base_margin,
                       count(*) filter (where w.part = 'AFTER') as after_days,
                       coalesce(sum(s.quantity) filter (where w.part = 'AFTER'), 0) as after_quantity,
                       coalesce(sum(x.value) filter (where w.part <> 'BASE'), 0) as promo_losses,
                       coalesce(sum(x.value) filter (where w.part = 'BASE'), 0) as base_losses
                from windows w
                left join days s on s.itm8 = w.itm8 and s.sale_date = w.day
                left join losses x on x.itm8 = w.itm8 and x.day = w.day
                group by w.itm8, w.start_date, w.end_date
            )
            select pm.listed, pm.itm8, pm.start_date as startDate, pm.end_date as endDate,
                   pm.regular_price as regularPrice, pm.promo_price as promoPrice,
                   pm.list_margin_percent as listMarginPercent,
                   s.promo_days as promoDays, s.promo_quantity as promoQuantity, s.promo_gross as promoGross,
                   s.promo_net as promoNet, s.promo_margin as promoMargin,
                   s.base_days as baseDays, s.base_quantity as baseQuantity, s.base_gross as baseGross,
                   s.base_net as baseNet, s.base_margin as baseMargin,
                   s.after_days as afterDays, s.after_quantity as afterQuantity,
                   s.promo_losses as promoLosses, s.base_losses as baseLosses,
                   p.name, p.brand, p.ean, n.code as regionCode, n.name as regionName
            from promos pm
            join sums s on s.itm8 = pm.itm8 and s.start_date = pm.start_date and s.end_date = pm.end_date
            left join products p on p.store_id = :storeId and p.itm8 = pm.itm8
            left join nomenclatures n on n.store_id = :storeId and n.code = p.region_code
            """, nativeQuery = true)
    List<Row> findPromotions(UUID storeId, LocalDate from, LocalDate to, LocalDate lastDay, LocalDate dataFrom,
            LocalDate dataTo, LocalDate referenceFrom, BigDecimal priceFactor, int minReferenceDays, int gapDays,
            int minDays, int maxDays, int baseDays, int afterDays);
}
