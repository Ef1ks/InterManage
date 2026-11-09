package com.intermanage.backend.product.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.entity.ProductCode;

/** Wszystkie kody artykułu: kartoteka, pary ITM8 + EAN z raportów kartoteki i multikody (także dawne). */
public interface ArticleCodeRepository extends Repository<ProductCode, UUID> {

    /** Wiersz kodu artykułu. {@code source}: MAIN, CATALOG (para z {@code product_eans}), MULTICODE. */
    interface CodeRow {
        String getEan();

        String getSource();

        String getName();

        Boolean getPrio();

        Boolean getCurrent();

        String getSaleUnit();

        BigDecimal getSalePack();

        BigDecimal getPriceFactor();

        BigDecimal getSalePrice();

        LocalDate getRemovalDate();

        LocalDateTime getFirstSeen();

        LocalDateTime getLastSeen();
    }

    /** Inny artykuł sklepu z tym samym kodem; {@code eanKey} to EAN bez zer wiodących. */
    interface OtherRow {
        String getEanKey();

        String getItm8();

        String getName();

        String getSource();
    }

    @Query(value = """
            select p.ean, 'MAIN' as source, p.name, null::boolean as prio, null::boolean as current,
                   null as sale_unit, null::numeric as sale_pack, null::numeric as price_factor,
                   null::numeric as sale_price, null::date as removal_date,
                   null::timestamp as first_seen, null::timestamp as last_seen
              from products p
             where p.store_id = :storeId and p.itm8 = :itm8
            union all
            select e.ean, 'CATALOG', p.name, null, null, null, null, null, null, null,
                   coalesce(fi.report_generated_at, fi.imported_at at time zone 'Europe/Warsaw'),
                   coalesce(li.report_generated_at, li.imported_at at time zone 'Europe/Warsaw')
              from product_eans e
              join products p on p.id = e.product_id
              join product_imports fi on fi.id = e.first_seen_import_id
              join product_imports li on li.id = e.last_seen_import_id
             where p.store_id = :storeId and p.itm8 = :itm8
            union all
            select c.ean, 'MULTICODE', c.name, c.prio, c.current, c.sale_unit, c.sale_pack, c.price_factor,
                   c.sale_price, c.removal_date,
                   coalesce(fi.report_generated_at, fi.imported_at at time zone 'Europe/Warsaw'),
                   coalesce(li.report_generated_at, li.imported_at at time zone 'Europe/Warsaw')
              from product_codes c
              join product_code_imports fi on fi.id = c.first_import_id
              join product_code_imports li on li.id = c.last_import_id
             where c.store_id = :storeId and c.itm8 = :itm8
            """, nativeQuery = true)
    List<CodeRow> findCodes(UUID storeId, String itm8);

    /**
     * Inne artykuły sklepu, do których te kody są przypisane teraz: główny EAN z kartoteki, obowiązujący multikod albo
     * para z raportu kartoteki. Jeden wiersz na kod i ITM8, ze źródłem w tej kolejności.
     */
    @Query(value = """
            select distinct on (o.ean_key, o.itm8) o.ean_key, o.itm8, o.name, o.source
              from (
                    select ltrim(p.ean, '0') as ean_key, p.itm8, p.name, 'MAIN' as source, 0 as rank
                      from products p
                     where p.store_id = :storeId and p.itm8 <> :itm8 and ltrim(p.ean, '0') in (:keys)
                    union all
                    select ltrim(c.ean, '0'), c.itm8, coalesce(p.name, c.name), 'MULTICODE', 1
                      from product_codes c
                      left join products p on p.store_id = c.store_id and p.itm8 = c.itm8
                     where c.store_id = :storeId and c.current and c.itm8 <> :itm8
                       and ltrim(c.ean, '0') in (:keys)
                    union all
                    select ltrim(e.ean, '0'), p.itm8, p.name, 'CATALOG', 2
                      from product_eans e
                      join products p on p.id = e.product_id
                     where e.ean in (:eans) and p.store_id = :storeId and p.itm8 <> :itm8
                   ) o
             order by o.ean_key, o.itm8, o.rank
            """, nativeQuery = true)
    List<OtherRow> findOtherArticles(UUID storeId, String itm8, Collection<String> keys, Collection<String> eans);
}
