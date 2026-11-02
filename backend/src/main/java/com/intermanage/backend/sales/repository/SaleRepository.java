package com.intermanage.backend.sales.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.sales.entity.Sale;

public interface SaleRepository extends JpaRepository<Sale, Sale.Key> {

    /** Wynik zapisu paczki: ile wierszy dopisano i ile z pominiętych (już zapisanych) ma w pliku inne wartości. */
    interface InsertCounts {
        long getCreated();

        long getConflicting();
    }

    /**
     * Zapis paczki wierszy raportu jednym zapytaniem: kolumny przychodzą jako tablice i są rozwijane przez
     * {@code unnest}. Zapisana sprzedaż jest jedynym źródłem prawdy, więc dzień artykułu, który już jest w bazie,
     * jest pomijany ({@code on conflict do nothing}), a nie nadpisywany. Porównanie z zapisanymi wierszami widzi stan
     * sprzed tego zapytania, więc liczy tylko wiersze z wcześniejszych importów, które różnią się od pliku.
     * Wiersze w paczce muszą być unikalne (sklep, dzień, ITM8, EAN) - pilnuje tego parser.
     */
    @Query(value = """
            with input as (
                select *
                from unnest(cast(:dates as date[]), cast(:itm8s as varchar[]), cast(:eans as varchar[]),
                            cast(:quantities as numeric[]), cast(:costValues as numeric[]),
                            cast(:saleValuesGross as numeric[]), cast(:vatValues as numeric[]),
                            cast(:marginValues as numeric[]), cast(:marginPercents as numeric[]))
                     as r(sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value, margin_value,
                          margin_percent)
            ), inserted as (
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                                   margin_value, margin_percent, import_id)
                select :storeId, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                       margin_value, margin_percent, :importId
                from input
                on conflict (store_id, sale_date, itm8, ean) do nothing
                returning 1
            )
            select (select count(*) from inserted) as created,
                   (select count(*)
                    from input i
                    join sales s on s.store_id = :storeId and s.sale_date = i.sale_date and s.itm8 = i.itm8
                                    and s.ean is not distinct from i.ean
                    where (s.quantity, s.cost_value, s.sale_value_gross, s.vat_value, s.margin_value,
                           s.margin_percent)
                          is distinct from (i.quantity, i.cost_value, i.sale_value_gross, i.vat_value,
                           i.margin_value, i.margin_percent)) as conflicting
            """, nativeQuery = true)
    InsertCounts insertNew(UUID storeId, UUID importId, LocalDate[] dates, String[] itm8s, String[] eans,
            BigDecimal[] quantities, BigDecimal[] costValues, BigDecimal[] saleValuesGross, BigDecimal[] vatValues,
            BigDecimal[] marginValues, BigDecimal[] marginPercents);

    interface Coverage {
        LocalDate getFirstDate();

        LocalDate getLastDate();
    }

    /** Pierwszy i ostatni dzień sprzedaży w bazie; z klucza głównego (sklep, dzień), bez czytania tabeli. */
    @Query(value = """
            select (select min(sale_date) from sales where store_id = :storeId) as first_date,
                   (select max(sale_date) from sales where store_id = :storeId) as last_date
            """, nativeQuery = true)
    Coverage findCoverage(UUID storeId);

    /** Wiersz porównania sprzedaży dwóch okresów; EAN i marka tylko przy artykułach. */
    interface ComparisonRow {
        String getCode();

        String getName();

        String getEan();

        String getBrand();

        BigDecimal getPreviousQuantity();

        BigDecimal getPreviousCostValue();

        BigDecimal getPreviousSaleValueGross();

        BigDecimal getPreviousSaleValueNet();

        BigDecimal getPreviousMarginValue();

        BigDecimal getCurrentQuantity();

        BigDecimal getCurrentCostValue();

        BigDecimal getCurrentSaleValueGross();

        BigDecimal getCurrentSaleValueNet();

        BigDecimal getCurrentMarginValue();
    }

    /** Warunek filtra nomenklatury na produkcie {@code p}: pusta tablica {@code codes} = bez filtra. */
    String IN_CODES = """
            (cardinality(cast(:codes as varchar[])) = 0
             or exists (select 1 from unnest(cast(:codes as varchar[])) c(code)
                        where p.subfamily_code like c.code || '%'))
            """;

    /**
     * Pełne miesiące okresów: {@code pf} (włącznie) do {@code pt} (bez) dla poprzedniego okresu, {@code cf}..{@code ct}
     * dla aktualnego. Pierwszy pełny miesiąc to miesiąc dnia "od", gdy wypada 1., inaczej następny; koniec to
     * pierwszy dzień miesiąca po dniu "do". Okres krótszy niż miesiąc nie ma pełnych miesięcy ({@code pf >= pt}).
     */
    String PERIOD_BOUNDS = """
            bounds as (
                select cast(date_trunc('month', cast(:previousFrom as timestamp) - interval '1 day')
                           + interval '1 month' as date) as pf,
                       cast(date_trunc('month', cast(:previousTo as timestamp) + interval '1 day') as date) as pt,
                       cast(date_trunc('month', cast(:currentFrom as timestamp) - interval '1 day')
                           + interval '1 month' as date) as cf,
                       cast(date_trunc('month', cast(:currentTo as timestamp) + interval '1 day') as date) as ct
            )
            """;

    /**
     * Sprzedaż okresów ({@code period} p / c) z całego sklepu: pełne miesiące z {@code sales_monthly}, a dni
     * niepełnych miesięcy na brzegach ze sprzedaży dziennej. Okresy mogą na siebie nachodzić albo być daleko od
     * siebie (rok do roku), więc każdy ma swoje wiersze.
     */
    String STORE_PARTS = """
            parts as (
                select 'p' as period, m.itm8, m.quantity, m.cost_value, m.sale_value_gross, m.sale_value_net,
                    m.margin_value
                from sales_monthly m cross join bounds b
                where m.store_id = :storeId and m.month >= b.pf and m.month < b.pt
                union all
                select 'p', s.itm8, s.quantity, s.cost_value, s.sale_value_gross, s.sale_value_net, s.margin_value
                from sales s cross join bounds b
                where s.store_id = :storeId and s.sale_date between :previousFrom and :previousTo
                    and (s.sale_date < b.pf or s.sale_date >= b.pt)
                union all
                select 'c' as period, m.itm8, m.quantity, m.cost_value, m.sale_value_gross, m.sale_value_net,
                    m.margin_value
                from sales_monthly m cross join bounds b
                where m.store_id = :storeId and m.month >= b.cf and m.month < b.ct
                union all
                select 'c', s.itm8, s.quantity, s.cost_value, s.sale_value_gross, s.sale_value_net, s.margin_value
                from sales s cross join bounds b
                where s.store_id = :storeId and s.sale_date between :currentFrom and :currentTo
                    and (s.sale_date < b.cf or s.sale_date >= b.ct)
            )
            """;

    /**
     * Jak {@link #STORE_PARTS}, ale tylko artykuły z kartoteki z podrodziną zaczynającą się od {@code parent}:
     * czytane od artykułów grupy po indeksach (sklep, ITM8, miesiąc / dzień), zamiast sumować cały sklep.
     */
    String GROUP_PARTS = """
            parts as (
                select 'p' as period, m.itm8, m.quantity, m.cost_value, m.sale_value_gross, m.sale_value_net,
                    m.margin_value
                from products q
                join sales_monthly m on m.store_id = q.store_id and m.itm8 = q.itm8
                cross join bounds b
                where q.store_id = :storeId and q.subfamily_code like :parent || '%'
                    and m.month >= b.pf and m.month < b.pt
                union all
                select 'p', s.itm8, s.quantity, s.cost_value, s.sale_value_gross, s.sale_value_net, s.margin_value
                from products q
                join sales s on s.store_id = q.store_id and s.itm8 = q.itm8
                    and s.sale_date between :previousFrom and :previousTo
                cross join bounds b
                where q.store_id = :storeId and q.subfamily_code like :parent || '%'
                    and (s.sale_date < b.pf or s.sale_date >= b.pt)
                union all
                select 'c' as period, m.itm8, m.quantity, m.cost_value, m.sale_value_gross, m.sale_value_net,
                    m.margin_value
                from products q
                join sales_monthly m on m.store_id = q.store_id and m.itm8 = q.itm8
                cross join bounds b
                where q.store_id = :storeId and q.subfamily_code like :parent || '%'
                    and m.month >= b.cf and m.month < b.ct
                union all
                select 'c', s.itm8, s.quantity, s.cost_value, s.sale_value_gross, s.sale_value_net, s.margin_value
                from products q
                join sales s on s.store_id = q.store_id and s.itm8 = q.itm8
                    and s.sale_date between :currentFrom and :currentTo
                cross join bounds b
                where q.store_id = :storeId and q.subfamily_code like :parent || '%'
                    and (s.sale_date < b.cf or s.sale_date >= b.ct)
            )
            """;

    /** Sumy artykułu (ITM8, ze wszystkich EAN-ów) w obu okresach; artykuł bez sprzedaży w okresie ma tam zera. */
    String ARTICLE_SUMS = """
            article as (
                select itm8,
                       coalesce(sum(quantity) filter (where period = 'p'), 0) as previous_quantity,
                       coalesce(sum(cost_value) filter (where period = 'p'), 0) as previous_cost_value,
                       coalesce(sum(sale_value_gross) filter (where period = 'p'), 0) as previous_sale_value_gross,
                       coalesce(sum(sale_value_net) filter (where period = 'p'), 0) as previous_sale_value_net,
                       coalesce(sum(margin_value) filter (where period = 'p'), 0) as previous_margin_value,
                       coalesce(sum(quantity) filter (where period = 'c'), 0) as current_quantity,
                       coalesce(sum(cost_value) filter (where period = 'c'), 0) as current_cost_value,
                       coalesce(sum(sale_value_gross) filter (where period = 'c'), 0) as current_sale_value_gross,
                       coalesce(sum(sale_value_net) filter (where period = 'c'), 0) as current_sale_value_net,
                       coalesce(sum(margin_value) filter (where period = 'c'), 0) as current_margin_value
                from parts
                group by itm8
            """;

    /** Artykuł z kartoteki i filtr nomenklatury dla sum {@code article}. */
    String SOLD = """
            ), sold as (
                select a.*, p.subfamily_code, p.name, p.ean, p.brand
                from article a
                left join products p on p.store_id = :storeId and p.itm8 = a.itm8
                where """ + IN_CODES + """
            )
            """;

    /**
     * Sprzedaż każdego artykułu (ITM8, ze wszystkich EAN-ów) w obu okresach, z produktem z kartoteki, jeśli jest.
     * Niepuste {@code codes} zostawia tylko artykuły z wybranych pozycji nomenklatury (dowolnego poziomu), więc odpada
     * też sprzedaż bez nomenklatury. Cały sklep: rok do roku to ok. 0,5 mln wierszy miesięcznych zamiast ok. 2,6 mln
     * dziennych.
     */
    String ARTICLE_SALES = "with " + PERIOD_BOUNDS + ", " + STORE_PARTS + ", " + ARTICLE_SUMS + SOLD;

    /**
     * Jak {@link #ARTICLE_SALES}, ale tylko artykuły z kartoteki z podrodziną zaczynającą się od {@code parent}
     * ({@link #GROUP_PARTS}), zamiast sumować cały sklep i odfiltrować grupę.
     */
    String GROUP_ARTICLE_SALES = "with " + PERIOD_BOUNDS + ", " + GROUP_PARTS + ", " + ARTICLE_SUMS + SOLD;

    /** Poziom nomenklatury z {@code sold}: kod to pierwsze {@code codeLength} cyfr podrodziny. */
    String BY_NOMENCLATURE = """
            select g.code, n.name,
                   cast(null as varchar) as ean, cast(null as varchar) as brand,
                   g.previous_quantity, g.previous_cost_value, g.previous_sale_value_gross, g.previous_sale_value_net,
                   g.previous_margin_value, g.current_quantity, g.current_cost_value, g.current_sale_value_gross,
                   g.current_sale_value_net, g.current_margin_value
            from (
                select left(subfamily_code, :codeLength) as code,
                       sum(previous_quantity) as previous_quantity,
                       sum(previous_cost_value) as previous_cost_value,
                       sum(previous_sale_value_gross) as previous_sale_value_gross,
                       sum(previous_sale_value_net) as previous_sale_value_net,
                       sum(previous_margin_value) as previous_margin_value,
                       sum(current_quantity) as current_quantity,
                       sum(current_cost_value) as current_cost_value,
                       sum(current_sale_value_gross) as current_sale_value_gross,
                       sum(current_sale_value_net) as current_sale_value_net,
                       sum(current_margin_value) as current_margin_value
                from sold
                group by 1
            ) g
            left join nomenclatures n on n.store_id = :storeId and n.code = g.code
            order by g.code nulls last
            """;

    /** Artykuły z {@code sold}, najpierw te, które sprzedały się najlepiej teraz. */
    String ARTICLES = """
            select itm8 as code, name, ean, brand,
                   previous_quantity, previous_cost_value, previous_sale_value_gross, previous_sale_value_net,
                   previous_margin_value, current_quantity, current_cost_value, current_sale_value_gross,
                   current_sale_value_net, current_margin_value
            from sold
            """;

    /**
     * Rejony całego sklepu ({@code codeLength} = 2). Artykuły bez podrodziny (spoza kartoteki) dają jeden wiersz
     * z pustym kodem, na końcu.
     */
    @Query(value = ARTICLE_SALES + BY_NOMENCLATURE, nativeQuery = true)
    List<ComparisonRow> compareByNomenclature(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, int codeLength, String[] codes);

    /**
     * Poziom nomenklatury w obrębie {@code parent} (kod rejonu daje rodziny, kod rodziny podrodziny): kod to pierwsze
     * {@code codeLength} cyfr podrodziny artykułu.
     */
    @Query(value = GROUP_ARTICLE_SALES + BY_NOMENCLATURE, nativeQuery = true)
    List<ComparisonRow> compareGroupByNomenclature(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, int codeLength, String parent, String[] codes);

    /** Artykuły jednej podrodziny ({@code parent}, 6 cyfr), najpierw te, które sprzedały się najlepiej teraz. */
    @Query(value = GROUP_ARTICLE_SALES + ARTICLES + """
            where subfamily_code = :parent
            order by current_sale_value_gross desc, previous_sale_value_gross desc, itm8
            """, nativeQuery = true)
    List<ComparisonRow> compareArticles(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String parent, String[] codes);

    /** Artykuły bez nomenklatury, także spoza kartoteki (bez nazwy, z samym ITM8). */
    @Query(value = ARTICLE_SALES + ARTICLES + """
            where subfamily_code is null
            order by current_sale_value_gross desc, previous_sale_value_gross desc, itm8
            """, nativeQuery = true)
    List<ComparisonRow> compareUnassignedArticles(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String[] codes);

    /**
     * Ustawienia zapytań raportu do końca transakcji: bez JIT (przy roku do roku kompilacja to ok. 100 ms z 800 ms
     * i nic nie daje) i {@code work_mem} 32 MB (przy domyślnych 4 MB sumy ok. 55 tys. artykułów lądują na dysku).
     */
    @Query(value = "select set_config('jit', 'off', true) || set_config('work_mem', '32MB', true)",
            nativeQuery = true)
    String tuneForReport();

    /** Sprzedaż jednego dnia; dni bez sprzedaży nie ma w wyniku. */
    interface DayRow {
        LocalDate getSaleDate();

        BigDecimal getQuantity();

        BigDecimal getCostValue();

        BigDecimal getSaleValueGross();

        BigDecimal getSaleValueNet();

        BigDecimal getMarginValue();
    }

    String DAY_SUMS = """
            select s.sale_date, sum(s.quantity) as quantity, sum(s.cost_value) as cost_value,
                   sum(s.sale_value_gross) as sale_value_gross, sum(s.sale_value_net) as sale_value_net,
                   sum(s.margin_value) as margin_value
            """;

    /** Sprzedaż artykułu (ITM8, ze wszystkich EAN-ów) dzień po dniu; z indeksu (sklep, ITM8, dzień). */
    @Query(value = DAY_SUMS + """
            from sales s
            where s.store_id = :storeId and s.itm8 = :itm8 and s.sale_date between :from and :to
            group by s.sale_date
            order by s.sale_date
            """, nativeQuery = true)
    List<DayRow> articleHistory(UUID storeId, String itm8, LocalDate from, LocalDate to);

    /**
     * Sprzedaż pozycji nomenklatury dzień po dniu: artykułów, których podrodzina zaczyna się od {@code prefix}
     * (pusty = cały sklep), a przy {@code unassigned} artykułów bez nomenklatury. Z filtrem {@code codes} jak porównanie.
     */
    @Query(value = DAY_SUMS + """
            from sales s
            left join products p on p.store_id = :storeId and p.itm8 = s.itm8
            where s.store_id = :storeId and s.sale_date between :from and :to
              and case when :unassigned then p.subfamily_code is null
                       else :prefix = '' or p.subfamily_code like :prefix || '%' end
              and """ + IN_CODES + """
            group by s.sale_date
            order by s.sale_date
            """, nativeQuery = true)
    List<DayRow> groupHistory(UUID storeId, String prefix, boolean unassigned, String[] codes, LocalDate from,
            LocalDate to);

    /** Sprzedaż rejonu nomenklatury za okres; pusty kod = sprzedaż artykułów bez nomenklatury (spoza kartoteki). */
    interface RegionSales {
        String getRegionCode();

        BigDecimal getCostValue();

        BigDecimal getSaleValueGross();

        BigDecimal getSaleValueNet();

        BigDecimal getMarginValue();
    }

    /** Pełne miesiące okresu {@code from}..{@code to}: {@code f} (włącznie) do {@code t} (bez), jak {@link #PERIOD_BOUNDS}. */
    String MONTH_BOUNDS = """
            with bounds as (
                select cast(date_trunc('month', cast(:from as timestamp) - interval '1 day')
                           + interval '1 month' as date) as f,
                       cast(date_trunc('month', cast(:to as timestamp) + interval '1 day') as date) as t
            )
            """;

    /** Sumy ITM8 całego sklepu w okresie. */
    String STORE_PERIOD_ARTICLES = MONTH_BOUNDS + """
            , parts as (
                select m.itm8, m.quantity, m.cost_value, m.sale_value_gross, m.sale_value_net, m.margin_value
                from sales_monthly m cross join bounds b
                where m.store_id = :storeId and m.month >= b.f and m.month < b.t
                union all
                select s.itm8, s.quantity, s.cost_value, s.sale_value_gross, s.sale_value_net, s.margin_value
                from sales s cross join bounds b
                where s.store_id = :storeId and s.sale_date between :from and :to
                    and (s.sale_date < b.f or s.sale_date >= b.t)
            ), article as (
                select itm8, sum(quantity) as quantity, sum(cost_value) as cost_value,
                       sum(sale_value_gross) as sale_value_gross, sum(sale_value_net) as sale_value_net,
                       sum(margin_value) as margin_value
                from parts
                group by itm8
            )
            """;

    /**
     * Sumy ITM8 w okresie tylko dla artykułów z kartoteki z podrodziną zaczynającą się od {@code parent}: czytane od
     * artykułów grupy po indeksach (sklep, ITM8, miesiąc / dzień), jak {@link #GROUP_PARTS}.
     */
    String GROUP_PERIOD_ARTICLES = MONTH_BOUNDS + """
            , parts as (
                select m.itm8, m.quantity, m.cost_value, m.sale_value_gross, m.sale_value_net, m.margin_value
                from products q
                join sales_monthly m on m.store_id = q.store_id and m.itm8 = q.itm8
                cross join bounds b
                where q.store_id = :storeId and q.subfamily_code like :parent || '%'
                    and m.month >= b.f and m.month < b.t
                union all
                select s.itm8, s.quantity, s.cost_value, s.sale_value_gross, s.sale_value_net, s.margin_value
                from products q
                join sales s on s.store_id = q.store_id and s.itm8 = q.itm8 and s.sale_date between :from and :to
                cross join bounds b
                where q.store_id = :storeId and q.subfamily_code like :parent || '%'
                    and (s.sale_date < b.f or s.sale_date >= b.t)
            ), article as (
                select itm8, sum(quantity) as quantity, sum(cost_value) as cost_value,
                       sum(sale_value_gross) as sale_value_gross, sum(sale_value_net) as sale_value_net,
                       sum(margin_value) as margin_value
                from parts
                group by itm8
            )
            """;

    /**
     * Sprzedaż okresu po rejonach nomenklatury: najpierw suma każdego ITM8 (pełne miesiące z {@code sales_monthly},
     * dni niepełnych miesięcy na brzegach ze sprzedaży dziennej), dopiero potem łączenie z kartoteką.
     */
    @Query(value = STORE_PERIOD_ARTICLES + """
            select coalesce(left(p.subfamily_code, 2), '') as region_code,
                   sum(a.cost_value) as cost_value, sum(a.sale_value_gross) as sale_value_gross,
                   sum(a.sale_value_net) as sale_value_net, sum(a.margin_value) as margin_value
            from article a
            left join products p on p.store_id = :storeId and p.itm8 = a.itm8
            group by 1
            """, nativeQuery = true)
    List<RegionSales> sumByRegion(UUID storeId, LocalDate from, LocalDate to);

    /** Sprzedaż artykułu w okresie z danymi z kartoteki (podrodzina pusta = spoza kartoteki albo bez nomenklatury). */
    interface ArticleSales {
        String getItm8();

        String getSubfamilyCode();

        String getName();

        String getEan();

        String getBrand();

        BigDecimal getQuantity();

        BigDecimal getCostValue();

        BigDecimal getSaleValueGross();

        BigDecimal getSaleValueNet();

        BigDecimal getMarginValue();
    }

    String PERIOD_ARTICLE_ROWS = """
            select a.itm8, p.subfamily_code, p.name, p.ean, p.brand, a.quantity, a.cost_value, a.sale_value_gross,
                   a.sale_value_net, a.margin_value
            from article a
            left join products p on p.store_id = :storeId and p.itm8 = a.itm8
            """;

    /** Sprzedaż w okresie artykułów, których podrodzina zaczyna się od {@code parent} (rozwinięty wiersz podsumowania). */
    @Query(value = GROUP_PERIOD_ARTICLES + PERIOD_ARTICLE_ROWS, nativeQuery = true)
    List<ArticleSales> sumGroupArticles(UUID storeId, LocalDate from, LocalDate to, String parent);

    /** Sprzedaż w okresie artykułów bez nomenklatury, także spoza kartoteki. */
    @Query(value = STORE_PERIOD_ARTICLES + PERIOD_ARTICLE_ROWS + """
            where p.subfamily_code is null
            """, nativeQuery = true)
    List<ArticleSales> sumUnassignedArticles(UUID storeId, LocalDate from, LocalDate to);

    /** Sprzedaż brutto rejonu w miesiącu (pierwszy dzień); pusty kod = artykuły bez nomenklatury. */
    interface MonthRegionSales {
        LocalDate getMonth();

        String getRegionCode();

        BigDecimal getSaleValueGross();
    }

    /**
     * Sprzedaż brutto po miesiącach i rejonach nomenklatury z {@code sales_monthly} (raport gratisów ma zawsze pełne
     * miesiące: {@code from} pierwszy dzień, {@code to} ostatni).
     */
    @Query(value = """
            select m.month as month, coalesce(left(p.subfamily_code, 2), '') as region_code,
                   sum(m.sale_value_gross) as sale_value_gross
            from sales_monthly m
            left join products p on p.store_id = :storeId and p.itm8 = m.itm8
            where m.store_id = :storeId and m.month between :from and :to
            group by 1, 2
            """, nativeQuery = true)
    List<MonthRegionSales> sumGrossByMonthAndRegion(UUID storeId, LocalDate from, LocalDate to);
}
