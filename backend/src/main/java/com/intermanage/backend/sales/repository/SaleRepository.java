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
     * Sprzedaż każdego artykułu (ITM8, ze wszystkich EAN-ów) w obu okresach, z produktem z kartoteki, jeśli jest.
     * Okresy mogą na siebie nachodzić albo być daleko od siebie (rok do roku), więc każdy dzień trafia do sum okresów,
     * w których leży. Artykuł bez sprzedaży w jednym z okresów ma tam zera. Niepuste {@code codes} zostawia tylko
     * artykuły z wybranych pozycji nomenklatury (dowolnego poziomu), więc odpada też sprzedaż bez nomenklatury.
     */
    String ARTICLE_SALES = """
            with article as (
                select itm8,
                       coalesce(sum(quantity) filter (where sale_date between :previousFrom and :previousTo), 0)
                           as previous_quantity,
                       coalesce(sum(cost_value) filter (where sale_date between :previousFrom and :previousTo), 0)
                           as previous_cost_value,
                       coalesce(sum(sale_value_gross) filter (where sale_date between :previousFrom and :previousTo), 0)
                           as previous_sale_value_gross,
                       coalesce(sum(sale_value_net) filter (where sale_date between :previousFrom and :previousTo), 0)
                           as previous_sale_value_net,
                       coalesce(sum(margin_value) filter (where sale_date between :previousFrom and :previousTo), 0)
                           as previous_margin_value,
                       coalesce(sum(quantity) filter (where sale_date between :currentFrom and :currentTo), 0)
                           as current_quantity,
                       coalesce(sum(cost_value) filter (where sale_date between :currentFrom and :currentTo), 0)
                           as current_cost_value,
                       coalesce(sum(sale_value_gross) filter (where sale_date between :currentFrom and :currentTo), 0)
                           as current_sale_value_gross,
                       coalesce(sum(sale_value_net) filter (where sale_date between :currentFrom and :currentTo), 0)
                           as current_sale_value_net,
                       coalesce(sum(margin_value) filter (where sale_date between :currentFrom and :currentTo), 0)
                           as current_margin_value
                from sales
                where store_id = :storeId
                  and (sale_date between :previousFrom and :previousTo or sale_date between :currentFrom and :currentTo)
                group by itm8
            ), sold as (
                select a.*, p.subfamily_code, p.name, p.ean, p.brand
                from article a
                left join products p on p.store_id = :storeId and p.itm8 = a.itm8
                where """ + IN_CODES + """
            )
            """;


    /**
     * Porównanie na jednym poziomie nomenklatury: kod to pierwsze {@code codeLength} cyfr podrodziny artykułu
     * (2 = rejony, 4 = rodziny, 6 = podrodziny), tylko w obrębie {@code parent} (pusty tekst = cały sklep).
     * Artykuły bez podrodziny (spoza kartoteki) dają na poziomie rejonów jeden wiersz z pustym kodem, na końcu.
     */
    @Query(value = ARTICLE_SALES + """
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
                where :parent = '' or subfamily_code like :parent || '%'
                group by 1
            ) g
            left join nomenclatures n on n.store_id = :storeId and n.code = g.code
            order by g.code nulls last
            """, nativeQuery = true)
    List<ComparisonRow> compareByNomenclature(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, int codeLength, String parent, String[] codes);

    /**
     * Porównanie artykułów jednej podrodziny ({@code subfamily}), a przy pustym tekście artykułów bez nomenklatury
     * (także spoza kartoteki: bez nazwy, z samym ITM8). Najpierw te, które sprzedały się najlepiej teraz.
     */
    @Query(value = ARTICLE_SALES + """
            select itm8 as code, name, ean, brand,
                   previous_quantity, previous_cost_value, previous_sale_value_gross, previous_sale_value_net,
                   previous_margin_value, current_quantity, current_cost_value, current_sale_value_gross,
                   current_sale_value_net, current_margin_value
            from sold
            where case when :subfamily = '' then subfamily_code is null else subfamily_code = :subfamily end
            order by current_sale_value_gross desc, previous_sale_value_gross desc, itm8
            """, nativeQuery = true)
    List<ComparisonRow> compareArticles(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String subfamily, String[] codes);

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

    /**
     * Sprzedaż okresu po rejonach nomenklatury: najpierw suma każdego ITM8 (z klucza sklep + dzień), dopiero potem
     * łączenie z kartoteką, żeby nie łączyć z produktami każdego wiersza dnia.
     */
    @Query(value = """
            with article as (
                select itm8, sum(cost_value) as cost_value, sum(sale_value_gross) as sale_value_gross,
                       sum(sale_value_net) as sale_value_net, sum(margin_value) as margin_value
                from sales
                where store_id = :storeId and sale_date between :from and :to
                group by itm8
            )
            select coalesce(left(p.subfamily_code, 2), '') as region_code,
                   sum(a.cost_value) as cost_value, sum(a.sale_value_gross) as sale_value_gross,
                   sum(a.sale_value_net) as sale_value_net, sum(a.margin_value) as margin_value
            from article a
            left join products p on p.store_id = :storeId and p.itm8 = a.itm8
            group by 1
            """, nativeQuery = true)
    List<RegionSales> sumByRegion(UUID storeId, LocalDate from, LocalDate to);

    /** Sprzedaż brutto rejonu w miesiącu (pierwszy dzień); pusty kod = artykuły bez nomenklatury. */
    interface MonthRegionSales {
        LocalDate getMonth();

        String getRegionCode();

        BigDecimal getSaleValueGross();
    }

    /** Sprzedaż brutto po miesiącach i rejonach nomenklatury, jak {@link #sumByRegion}: najpierw suma ITM8. */
    @Query(value = """
            with article as (
                select cast(date_trunc('month', sale_date) as date) as month, itm8,
                       sum(sale_value_gross) as sale_value_gross
                from sales
                where store_id = :storeId and sale_date between :from and :to
                group by 1, 2
            )
            select a.month as month, coalesce(left(p.subfamily_code, 2), '') as region_code,
                   sum(a.sale_value_gross) as sale_value_gross
            from article a
            left join products p on p.store_id = :storeId and p.itm8 = a.itm8
            group by 1, 2
            """, nativeQuery = true)
    List<MonthRegionSales> sumGrossByMonthAndRegion(UUID storeId, LocalDate from, LocalDate to);
}
