package com.intermanage.backend.stocktake.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.stocktake.entity.Stocktake;

public interface StocktakeRepository extends JpaRepository<Stocktake, UUID> {

    Optional<Stocktake> findByStore_IdAndNumberAndStocktakeDate(UUID storeId, String number, LocalDate stocktakeDate);

    Optional<Stocktake> findByIdAndStore_Id(UUID id, UUID storeId);

    /** Blokada importu inwentury sklepu do końca transakcji, jak przy innych importach. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);

    interface MergeCounts {
        long getCreated();

        long getUpdated();
    }

    /**
     * Zapisuje artykuły z pliku: nowe dopisuje, znane aktualizuje, a artykuł, który wrócił do raportu, znów ma różnicę.
     * Wydruki działów bywają wgrywane w dowolnej kolejności, więc starszy wydruk nie nadpisuje nowszego. {@code updated}
     * liczy artykuły ze zmianą (porównanie widzi stan sprzed zapytania). ITM8 w tablicach są unikalne: pilnuje parser.
     */
    @Query(value = """
            with input as (
                select *
                from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[]), cast(:names as text[]),
                            cast(:brands as text[]), cast(:blockTypes as text[]), cast(:realQuantities as numeric[]),
                            cast(:theoreticalQuantities as numeric[]), cast(:differenceQuantities as numeric[]),
                            cast(:differenceValues as numeric[]), cast(:articleKinds as text[]))
                     as r(itm8, ean, name, brand, block_type, real_quantity, theoretical_quantity, difference_quantity,
                          difference_value, article_kind)
            ), changed as (
                select count(*) as n
                from input i
                join stocktake_lines l on l.stocktake_id = :stocktakeId and l.itm8 = i.itm8
                where l.report_generated_at <= :generatedAt
                  and ((l.real_quantity, l.theoretical_quantity, l.difference_value) is distinct from
                       (i.real_quantity, i.theoretical_quantity, i.difference_value)
                       or l.resolved_import_id is not null)
            ), saved as (
                insert into stocktake_lines (stocktake_id, itm8, ean, name, brand, block_type, real_quantity,
                                             theoretical_quantity, difference_quantity, difference_value, article_kind,
                                             first_difference_value, report_generated_at, first_import_id,
                                             last_import_id)
                select :stocktakeId, itm8, ean, name, brand, block_type, real_quantity, theoretical_quantity,
                       difference_quantity, difference_value, article_kind, difference_value, :generatedAt, :importId,
                       :importId
                from input
                on conflict (stocktake_id, itm8) do update
                    set ean = excluded.ean, name = excluded.name, brand = excluded.brand,
                        block_type = excluded.block_type, real_quantity = excluded.real_quantity,
                        theoretical_quantity = excluded.theoretical_quantity,
                        difference_quantity = excluded.difference_quantity,
                        difference_value = excluded.difference_value, article_kind = excluded.article_kind,
                        report_generated_at = excluded.report_generated_at, last_import_id = excluded.last_import_id,
                        resolved_import_id = null
                    where stocktake_lines.report_generated_at <= excluded.report_generated_at
                returning (xmax = 0) as created
            )
            select (select count(*) from saved where created) as created, (select n from changed) as updated
            """, nativeQuery = true)
    MergeCounts merge(UUID stocktakeId, UUID importId, LocalDateTime generatedAt, String[] itm8s, String[] eans,
            String[] names, String[] brands, String[] blockTypes, BigDecimal[] realQuantities,
            BigDecimal[] theoreticalQuantities, BigDecimal[] differenceQuantities, BigDecimal[] differenceValues,
            String[] articleKinds);

    /** Raport pokazuje tylko rozbieżności, więc artykuł spoza nowszego raportu całego sklepu nie ma już różnicy. */
    @Modifying
    @Query(value = """
            update stocktake_lines set resolved_import_id = :importId
            where stocktake_id = :stocktakeId and resolved_import_id is null and last_import_id <> :importId
              and report_generated_at < :generatedAt
            """, nativeQuery = true)
    int resolveMissing(UUID stocktakeId, UUID importId, LocalDateTime generatedAt);

    interface Line {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getBlockType();

        BigDecimal getRealQuantity();

        BigDecimal getTheoreticalQuantity();

        BigDecimal getDifferenceQuantity();

        BigDecimal getDifferenceValue();

        BigDecimal getFirstDifferenceValue();

        String getArticleKind();

        String getRegionCode();

        String getRegionName();

        BigDecimal getSoldAfter();

        boolean getResolved();
    }

    /** Wszystkie artykuły inwentury z rejonem z kartoteki i sprzedażą z okresu po inwenturze. */
    @Query(value = """
            with sold as (
                select itm8, sum(quantity) as quantity
                from sales_by_article
                where store_id = :storeId and sale_date between :salesFrom and :salesTo
                group by itm8
            )
            select l.itm8 as itm8, l.ean as ean, l.name as name, l.brand as brand, l.block_type as blockType,
                   l.real_quantity as realQuantity, l.theoretical_quantity as theoreticalQuantity,
                   l.difference_quantity as differenceQuantity, l.difference_value as differenceValue,
                   l.first_difference_value as firstDifferenceValue, l.article_kind as articleKind,
                   p.region_code as regionCode, n.name as regionName, coalesce(s.quantity, 0) as soldAfter,
                   l.resolved_import_id is not null as resolved
            from stocktake_lines l
            left join products p on p.store_id = :storeId and p.itm8 = l.itm8
            left join nomenclatures n on n.store_id = :storeId and n.code = p.region_code
            left join sold s on s.itm8 = l.itm8
            where l.stocktake_id = :stocktakeId
            """, nativeQuery = true)
    List<Line> findLines(UUID stocktakeId, UUID storeId, LocalDate salesFrom, LocalDate salesTo);

    @Query(value = """
            select max(sale_date) from sales_by_article
            where store_id = :storeId and sale_date between :salesFrom and :salesTo
            """, nativeQuery = true)
    LocalDate findLastSaleDay(UUID storeId, LocalDate salesFrom, LocalDate salesTo);

    interface ArticleQuantity {
        String getItm8();

        BigDecimal getQuantity();
    }

    /** Towar z innych sklepów: w MM przyjęcie ma ilość na minus. */
    @Query(value = """
            select l.itm8 as itm8, -sum(l.quantity) as quantity
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'MM' and l.quantity < 0
              and d.document_date >= :since and d.document_date < :until
            group by l.itm8
            """, nativeQuery = true)
    List<ArticleQuantity> sumTransfersIn(UUID storeId, LocalDateTime since, LocalDateTime until);

    interface Listed {
        UUID getId();

        String getNumber();

        String getName();

        LocalDate getStocktakeDate();

        long getLineCount();

        BigDecimal getNetValue();

        Instant getLastImportAt();
    }

    @Query(value = """
            select s.id as id, s.number as number, s.name as name, s.stocktake_date as stocktakeDate,
                   coalesce(l.line_count, 0) as lineCount, coalesce(l.net_value, 0) as netValue,
                   i.last_import_at as lastImportAt
            from stocktakes s
            left join lateral (
                select count(*) as line_count, sum(difference_value) as net_value
                from stocktake_lines where stocktake_id = s.id and resolved_import_id is null
            ) l on true
            left join lateral (
                select max(imported_at) as last_import_at from stocktake_imports where stocktake_id = s.id
            ) i on true
            where s.store_id = :storeId and s.deleted_at is null
            order by s.stocktake_date desc, s.number desc
            """, nativeQuery = true)
    List<Listed> findListed(UUID storeId);
}
