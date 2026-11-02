package com.intermanage.backend.document.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;

public interface DocumentLineRepository extends JpaRepository<DocumentLine, UUID> {

    /**
     * Linie dokumentów danego typu z ceną zakupu dla podanych ITM8, od najnowszego dokumentu. Pierwsza linia danego
     * ITM8 to jego ostatnia dostawa (kalkulator cen garmażu bierze z niej cenę składnika).
     */
    @Query("""
            select l from DocumentLine l join fetch l.document d
            where d.store.id = :storeId and d.type = :type and l.itm8 in :itm8s
              and l.purchasePrice > 0 and l.quantity > 0
            order by d.documentDate desc, d.number desc, l.lineNumber desc""")
    List<DocumentLine> findPricedLines(UUID storeId, DocumentType type, Collection<String> itm8s);

    /** Cena zakupu artykułu w linii przyjęcia od dostawcy. */
    interface SupplierPrice {
        String getItm8();

        String getEan();

        UUID getDocumentId();

        String getDocumentNumber();

        LocalDateTime getDocumentDate();

        BigDecimal getPurchasePrice();
    }

    /**
     * Ceny zakupu z linii przyjęć PZ od dostawców (kod z linii) z dokumentów z datą w [from, to), od najstarszego
     * (weryfikacja cen: ostatnia cena z bazy przed dostawą od innego dostawcy). Tylko artykuły o podanych ITM8 albo
     * EAN-ach ({@code eans} bez zer wiodących), bo dostaw z bazy z roku są dziesiątki tysięcy.
     */
    @Query(value = """
            select l.itm8 as itm8, l.ean as ean, d.id as document_id, d.number as document_number,
                   d.document_date as document_date, l.purchase_price as purchase_price
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.supplier_code in (:supplierCodes)
              and l.purchase_price > 0 and l.quantity > 0
              and d.document_date >= :from and d.document_date < :to
              and (l.itm8 = any(cast(:itm8s as varchar[]))
                   or ltrim(btrim(l.ean), '0') = any(cast(:eans as varchar[])))
            order by d.document_date, d.number, l.line_number
            """, nativeQuery = true)
    List<SupplierPrice> findSupplierPrices(UUID storeId, Collection<String> supplierCodes, LocalDateTime from,
            LocalDateTime to, String[] itm8s, String[] eans);

    /** Dostawa artykułu: przyjęcie PZ z sumą jego linii (ilość, wartość zakupu netto). */
    interface ArticleDelivery {
        UUID getDocumentId();

        String getDocumentNumber();

        LocalDateTime getDocumentDate();

        String getSupplierCode();

        String getSupplierName();

        BigDecimal getQuantity();

        BigDecimal getPurchaseValue();
    }

    /**
     * Ostatnie dostawy artykułu (przyjęcia PZ z ilością > 0), od najnowszej. Linie tego samego ITM8 w jednym
     * przyjęciu są sumowane, więc cena to wartość / ilość.
     */
    @Query(value = """
            select d.id as document_id, d.number as document_number, d.document_date as document_date,
                   max(l.supplier_code) as supplier_code, max(l.supplier_name) as supplier_name,
                   sum(l.quantity) as quantity, coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.itm8 = :itm8 and l.quantity > 0
            group by d.id, d.number, d.document_date
            order by d.document_date desc, length(d.number) desc, d.number desc
            limit :limit
            """, nativeQuery = true)
    List<ArticleDelivery> findArticleDeliveries(UUID storeId, String itm8, int limit);

    /**
     * Likwidacje (PL) artykułu dzień po dniu wg daty dokumentu, {@code from} i {@code to} włącznie: wartość zakupu
     * netto linii (wydanie, więc z minusem). Dni bez likwidacji nie ma w wyniku.
     */
    @Query(value = """
            select cast(d.document_date as date) as day, coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PL' and l.itm8 = :itm8
              and d.document_date >= cast(:from as timestamp)
              and d.document_date < cast(:to as timestamp) + interval '1 day'
            group by 1
            order by 1
            """, nativeQuery = true)
    List<DocumentRepository.DayValue> sumArticleLossesByDay(UUID storeId, String itm8, LocalDate from, LocalDate to);

    /**
     * Likwidacje (PL) pozycji nomenklatury dzień po dniu, pogrupowane jak sprzedaż w raportach: po podrodzinie
     * artykułu z kartoteki, a bez niego po nomenklaturze linii. {@code prefix} pusty = cały sklep, {@code unassigned}
     * = linie bez nomenklatury, {@code codes} (dowolny poziom) zawęża jak filtr porównania.
     */
    @Query(value = """
            select cast(d.document_date as date) as day, coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            left join products p on p.store_id = :storeId and p.itm8 = l.itm8
            cross join lateral (select coalesce(p.subfamily_code,
                    nullif(replace(l.nomenclature_code, ' ', ''), '')) as code) n
            where d.store_id = :storeId and d.type = 'PL'
              and d.document_date >= cast(:from as timestamp)
              and d.document_date < cast(:to as timestamp) + interval '1 day'
              and case when :unassigned then n.code is null else :prefix = '' or n.code like :prefix || '%' end
              and (cardinality(cast(:codes as varchar[])) = 0
                   or exists (select 1 from unnest(cast(:codes as varchar[])) c(code) where n.code like c.code || '%'))
            group by 1
            order by 1
            """, nativeQuery = true)
    List<DocumentRepository.DayValue> sumGroupLossesByDay(UUID storeId, String prefix, boolean unassigned,
            String[] codes, LocalDate from, LocalDate to);

    /** Wartość zakupu linii jednego typu dokumentu w rejonie nomenklatury (pusty kod = linie bez nomenklatury). */
    interface RegionValue {
        String getRegionCode();

        String getType();

        BigDecimal getPurchaseValue();
    }

    /**
     * Wartość zakupu linii dokumentów podanych typów w okresie (wg daty dokumentu, oba końce włącznie), po rejonie
     * nomenklatury linii (pierwsze dwie cyfry kodu). Wydania z magazynu (likwidacje, RW) mają wartość z minusem.
     */
    @Query(value = """
            select coalesce(left(replace(l.nomenclature_code, ' ', ''), 2), '') as region_code, d.type as type,
                   coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type in (:types)
              and d.document_date >= cast(:from as timestamp)
              and d.document_date < cast(:to as timestamp) + interval '1 day'
            group by 1, 2
            """, nativeQuery = true)
    List<RegionValue> sumByRegion(UUID storeId, Collection<String> types, LocalDate from, LocalDate to);

    /** Wartość zakupu linii artykułu w podrodzinie nomenklatury linii (kod bez spacji, pusty = bez nomenklatury). */
    interface ArticleValue {
        String getItm8();

        String getNomenclatureCode();

        String getName();

        String getEan();

        String getBrand();

        BigDecimal getPurchaseValue();
    }

    /**
     * Jak {@link #sumByRegion}, ale po artykułach: linie dokumentów jednego typu, których nomenklatura linii zaczyna
     * się od {@code prefix}, a przy {@code unassigned} linie bez nomenklatury. Nazwa, EAN i marka z linii.
     */
    @Query(value = """
            select l.itm8 as itm8, n.code as nomenclature_code, max(l.name) as name, max(l.ean) as ean,
                   max(l.brand) as brand, coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            cross join lateral (select coalesce(replace(l.nomenclature_code, ' ', ''), '') as code) n
            where d.store_id = :storeId and d.type = :type
              and d.document_date >= cast(:from as timestamp)
              and d.document_date < cast(:to as timestamp) + interval '1 day'
              and case when :unassigned then left(n.code, 2) = '' else n.code like :prefix || '%' end
            group by 1, 2
            """, nativeQuery = true)
    List<ArticleValue> sumGroupArticles(UUID storeId, String type, String prefix, boolean unassigned, LocalDate from,
            LocalDate to);

    /** Dostawca (przy bazie z marką) artykułów z przyjęć PZ: kiedy ostatnio przywiózł i ile z podanych artykułów. */
    interface RecentSupplier {
        String getSupplierCode();

        String getSupplierName();

        String getBrand();

        LocalDateTime getLastDeliveryDate();

        long getProductCount();
    }

    /**
     * Ostatni dostawcy podanych artykułów z przyjęć PZ, od najświeższej dostawy. Dostawcy z {@code brandSuppliers}
     * (baza) są rozbici na marki artykułów, bo gratis daje producent.
     */
    @Query(value = """
            select l.supplier_code as supplier_code, max(l.supplier_name) as supplier_name,
                   case when l.supplier_code in (:brandSuppliers) then nullif(trim(l.brand), '') end as brand,
                   max(d.document_date) as last_delivery_date, count(distinct l.itm8) as product_count
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.itm8 in (:itm8s) and l.quantity > 0
              and l.supplier_code is not null
            group by 1, 3
            order by 4 desc
            limit :limit
            """, nativeQuery = true)
    List<RecentSupplier> findRecentSuppliers(UUID storeId, Collection<String> itm8s,
            Collection<String> brandSuppliers, int limit);

    /** Linie protokołów artykułu w okresie raportu i w okresie porównywanym. */
    interface ProtocolArticle {
        String getItm8();

        String getName();

        String getEan();

        String getBrand();

        String getNomenclatureCode();

        String getSupplierCode();

        String getSupplierName();

        BigDecimal getCurrentQuantity();

        BigDecimal getCurrentPurchaseValue();

        BigDecimal getCurrentSaleValueGross();

        BigDecimal getPreviousQuantity();

        BigDecimal getPreviousPurchaseValue();

        BigDecimal getPreviousSaleValueGross();
    }

    /**
     * Linie dokumentów jednego typu po artykułach (wg daty dokumentu, oba końce włącznie) w okresie {@code current…}
     * i, przy {@code withPrevious}, w okresie {@code previous…} (okresy mogą na siebie nachodzić). Wartość zakupu bez
     * minusa: protokoły to wydania z magazynu. Nazwa, EAN, marka, nomenklatura (bez spacji) i dostawca z linii.
     */
    @Query(value = """
            with lines as (
                select l.*,
                       d.document_date >= cast(:currentFrom as timestamp)
                           and d.document_date < cast(:currentTo as timestamp) + interval '1 day' as in_current,
                       :withPrevious and d.document_date >= cast(:previousFrom as timestamp)
                           and d.document_date < cast(:previousTo as timestamp) + interval '1 day' as in_previous
                from document_lines l
                join documents d on d.id = l.document_id
                where d.store_id = :storeId and d.type = :type
                  and ((d.document_date >= cast(:currentFrom as timestamp)
                          and d.document_date < cast(:currentTo as timestamp) + interval '1 day')
                       or (:withPrevious and d.document_date >= cast(:previousFrom as timestamp)
                          and d.document_date < cast(:previousTo as timestamp) + interval '1 day'))
            )
            select itm8, max(name) as name, max(ean) as ean, max(nullif(trim(brand), '')) as brand,
                   max(nullif(replace(nomenclature_code, ' ', ''), '')) as nomenclature_code,
                   max(supplier_code) as supplier_code, max(supplier_name) as supplier_name,
                   coalesce(sum(quantity) filter (where in_current), 0) as current_quantity,
                   coalesce(-sum(purchase_value) filter (where in_current), 0) as current_purchase_value,
                   coalesce(sum(sale_value_gross) filter (where in_current), 0) as current_sale_value_gross,
                   coalesce(sum(quantity) filter (where in_previous), 0) as previous_quantity,
                   coalesce(-sum(purchase_value) filter (where in_previous), 0) as previous_purchase_value,
                   coalesce(sum(sale_value_gross) filter (where in_previous), 0) as previous_sale_value_gross
            from lines
            group by itm8
            """, nativeQuery = true)
    List<ProtocolArticle> sumProtocolArticles(UUID storeId, String type, LocalDate currentFrom, LocalDate currentTo,
            boolean withPrevious, LocalDate previousFrom, LocalDate previousTo);

    /** Ostatni dostawca artykułu: z najnowszego przyjęcia PZ z ilością > 0. */
    interface LastSupplier {
        String getItm8();

        String getSupplierCode();

        String getSupplierName();
    }

    /** Ostatni dostawca każdego z podanych artykułów z przyjęć PZ (artykułów bez przyjęcia nie ma w wyniku). */
    @Query(value = """
            select distinct on (l.itm8) l.itm8 as itm8, l.supplier_code as supplier_code,
                   l.supplier_name as supplier_name
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and l.itm8 in (:itm8s)
              and l.supplier_code is not null
            order by l.itm8, d.document_date desc, d.number desc
            """, nativeQuery = true)
    List<LastSupplier> findLastSuppliers(UUID storeId, Collection<String> itm8s);
}
