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
import com.intermanage.backend.leaflet.repository.PromoLeafletRepository;
import com.intermanage.backend.promotion.repository.PromotionReportRepository;

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

    /** Dostawa artykułu z historii: ilość i wartość z bonu oraz po korektach przypiętych do przyjęcia. */
    /** Linie przypiętych korekt przyjęć z CTE {@code lines}, po artykule, CTE {@code fixes}. */
    String DELIVERY_FIXES = """
            fixes as (
                select c.delivery_id, cl.itm8, sum(cl.quantity) as quantity, sum(cl.purchase_value) as purchase_value
                from delivery_corrections c
                join document_lines cl on cl.document_id = c.correction_id
                where c.unlinked_at is null and c.delivery_id in (select document_id from lines)
                group by c.delivery_id, cl.itm8
            )
            """;

    interface DeliveryHistory {
        UUID getDocumentId();

        String getDocumentNumber();

        LocalDateTime getDocumentDate();

        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getNomenclatureCode();

        String getSupplierCode();

        String getSupplierName();

        BigDecimal getQuantity();

        BigDecimal getPurchaseValue();

        BigDecimal getCorrectedQuantity();

        BigDecimal getCorrectedValue();

        boolean getCorrected();
    }

    /**
     * Dostawy z datą w [since, until) artykułów przyjętych w [from, until) albo w przyjęciach {@code documentIds}, od
     * najstarszej. Linie tego samego ITM8 w przyjęciu są sumowane; linie bez wartości (gratis) nie liczą się do ceny.
     * Tylko artykuły, które mogą być przepłacone: sprawdzana dostawa jest droższa niż {@code priceFactor} × najniższa
     * cena powyżej {@code minPrice} z całej historii, więc bez nich raport nie wczytuje dziesiątek tysięcy wierszy.
     */
    @Query(value = """
            with lines as (
                select d.id as document_id, d.number as document_number, d.document_date, l.itm8,
                       max(l.ean) as ean, max(l.name) as name, max(l.brand) as brand,
                       max(l.nomenclature_code) as nomenclature_code, max(l.supplier_code) as supplier_code,
                       max(l.supplier_name) as supplier_name,
                       sum(l.quantity) as quantity, sum(l.purchase_value) as purchase_value
                from document_lines l
                join documents d on d.id = l.document_id
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and l.purchase_value > 0
                  and d.document_date >= :since and d.document_date < :until
                  and l.itm8 in (select c.itm8 from document_lines c
                                 join documents cd on cd.id = c.document_id
                                 where cd.store_id = :storeId and cd.type = 'PZ'
                                   and ((cd.document_date >= :from and cd.document_date < :until)
                                        or cast(cd.id as varchar) = any(cast(:documentIds as varchar[]))))
                group by d.id, d.number, d.document_date, l.itm8
            ), """ + DELIVERY_FIXES + """
            , history as (
                select l.*, l.quantity + coalesce(f.quantity, 0) as corrected_quantity,
                       l.purchase_value + coalesce(f.purchase_value, 0) as corrected_value,
                       f.delivery_id is not null as corrected,
                       min(round(l.purchase_value / l.quantity, 4))
                           filter (where round(l.purchase_value / l.quantity, 4) > :minPrice)
                           over (partition by l.itm8) as lowest_price
                from lines l
                left join fixes f on f.delivery_id = l.document_id and f.itm8 = l.itm8
            ), suspects as (
                select distinct h.itm8
                from history h
                where (h.document_date >= :from or cast(h.document_id as varchar) = any(cast(:documentIds as varchar[])))
                  and h.corrected_quantity > 0 and h.corrected_value > 0
                  and round(h.corrected_value / h.corrected_quantity, 4) > h.lowest_price * :priceFactor
            )
            select h.document_id as documentId, h.document_number as documentNumber,
                   h.document_date as documentDate, h.itm8 as itm8, h.ean as ean, h.name as name, h.brand as brand,
                   h.nomenclature_code as nomenclatureCode, h.supplier_code as supplierCode,
                   h.supplier_name as supplierName,
                   h.quantity as quantity, h.purchase_value as purchaseValue,
                   h.corrected_quantity as correctedQuantity, h.corrected_value as correctedValue,
                   h.corrected as corrected
            from history h
            where h.itm8 in (select itm8 from suspects)
            order by h.document_date, length(h.document_number), h.document_number
            """, nativeQuery = true)
    List<DeliveryHistory> findDeliveryHistory(UUID storeId, LocalDateTime since, LocalDateTime from,
            LocalDateTime until, String[] documentIds, BigDecimal minPrice, BigDecimal priceFactor);

    interface DeliveryMargin {
        UUID getDocumentId();

        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getNomenclatureCode();

        String getSupplierCode();

        BigDecimal getSalePrice();

        Short getVatRate();

        BigDecimal getCorrectedQuantity();

        BigDecimal getCorrectedValue();

        boolean getCorrected();

        BigDecimal getListedPrice();

        BigDecimal getTillPrice();

        boolean getLeaflet();

        BigDecimal getReferencePrice();

        BigDecimal getCatalogPrice();
    }

    /**
     * Artykuły przyjęć PZ z datą w [from, until) i przyjęć {@code documentIds}, po korektach, z tym, co wiadomo o cenie
     * sprzedaży w dniu przyjęcia: {@code salePrice} z dokumentu (w promocji to cena promocyjna), {@code listedPrice}
     * cena zwykła z promocji trwającej tego dnia na liście promocji, {@code tillPrice} to samo z plików kasy (promocje
     * założone ręcznie są tylko tam), {@code leaflet} artykuł w gazetce tego dnia,
     * {@code referencePrice} cena zwykła ze sprzedaży ({@link PromotionReportRepository#REGULAR_PRICES}),
     * {@code catalogPrice} cena z kartoteki. Linie bez wartości (gratis) się nie liczą.
     */
    @Query(value = """
            with lines as (
                select d.id as document_id, cast(d.document_date as date) as day, l.itm8,
                       max(l.ean) as ean, max(l.name) as name, max(l.brand) as brand,
                       max(l.nomenclature_code) as nomenclature_code, max(l.supplier_code) as supplier_code,
                       max(l.sale_price_document) as sale_price, max(l.vat_rate) as vat_rate,
                       sum(l.quantity) as quantity, sum(l.purchase_value) as purchase_value
                from document_lines l
                join documents d on d.id = l.document_id
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and l.purchase_value > 0
                  and ((d.document_date >= :from and d.document_date < :until)
                       or cast(d.id as varchar) = any(cast(:documentIds as varchar[])))
                group by d.id, d.document_date, l.itm8
            ), """ + DELIVERY_FIXES + ", leaflet as (" + PromoLeafletRepository.ARTICLE_PERIODS + "), "
            + PromotionReportRepository.REGULAR_PRICES + """
            , listed as (
                select l.document_id, l.itm8, max(p.regular_sale_price) as price
                from lines l
                join promotions p on p.store_id = :storeId and p.itm8 = l.itm8
                                 and l.day between p.start_date and p.end_date
                group by l.document_id, l.itm8
            ), till as (
                select l.document_id, l.itm8, max(t.regular_price) as price
                from lines l
                join till_promotions t on t.store_id = :storeId and t.itm8 = l.itm8
                                      and l.day between t.promo_from and t.promo_to
                group by l.document_id, l.itm8
            ), in_leaflet as (
                select distinct l.document_id, l.itm8
                from lines l
                join leaflet g on g.itm8 = l.itm8 and l.day between g.date_from and g.date_to
            )
            select l.document_id as documentId, l.itm8 as itm8, l.ean as ean, l.name as name, l.brand as brand,
                   l.nomenclature_code as nomenclatureCode, l.supplier_code as supplierCode,
                   l.sale_price as salePrice, l.vat_rate as vatRate,
                   l.quantity + coalesce(f.quantity, 0) as correctedQuantity,
                   l.purchase_value + coalesce(f.purchase_value, 0) as correctedValue,
                   f.delivery_id is not null as corrected,
                   p.price as listedPrice, t.price as tillPrice, g.itm8 is not null as leaflet,
                   round(cast(r.price as numeric), 2) as referencePrice,
                   c.sale_price as catalogPrice
            from lines l
            left join fixes f on f.delivery_id = l.document_id and f.itm8 = l.itm8
            left join listed p on p.document_id = l.document_id and p.itm8 = l.itm8
            left join till t on t.document_id = l.document_id and t.itm8 = l.itm8
            left join in_leaflet g on g.document_id = l.document_id and g.itm8 = l.itm8
            left join reference r on r.itm8 = l.itm8
                                 and r.month = cast(date_trunc('month', l.day) as date)
            left join products c on c.store_id = :storeId and c.itm8 = l.itm8
            """, nativeQuery = true)
    List<DeliveryMargin> findDeliveryMargins(UUID storeId, LocalDateTime from, LocalDateTime until,
            String[] documentIds, LocalDate dataFrom, LocalDate dataTo, LocalDate referenceFrom, int minReferenceDays);

    /**
     * Linie artykułu {@code :itm8}. Dostawca spoza bazy przywozi towar pod własnym, lokalnym ITM8 ({@code 99…}),
     * którego nie ma w kartotece, więc taka linia należy do artykułu po EAN-ie (główny, dawne pary, multikody; bez zer
     * wiodących, też krótkie kody towaru luzem, np. 219394). Linia z ITM8 z kartoteki należy tylko do swojego
     * artykułu, bo EAN-y bywają przenoszone między artykułami. Warunek {@link #ARTICLE_LINE} korzysta z {@link #ARTICLE_EANS}.
     */
    String ARTICLE_EANS = """
            with article_eans as (
                select ltrim(btrim(p.ean), '0') as ean from products p where p.store_id = :storeId and p.itm8 = :itm8
                union
                select ltrim(btrim(pe.ean), '0') from product_eans pe join products p on p.id = pe.product_id
                where p.store_id = :storeId and p.itm8 = :itm8
                union
                select ltrim(btrim(pc.ean), '0') from product_codes pc
                where pc.store_id = :storeId and pc.itm8 = :itm8 and pc.current
            )
            """;

    String ARTICLE_LINE = """
            (l.itm8 = :itm8
             or ltrim(btrim(l.ean), '0') in (select ean from article_eans where ean <> '')
                and not exists (select 1 from products o where o.store_id = :storeId and o.itm8 = l.itm8))
            """;

    /**
     * Ostatnie dostawy artykułu (przyjęcia PZ z ilością > 0), od najnowszej. Linie tego samego ITM8 w jednym
     * przyjęciu są sumowane, więc cena to wartość / ilość.
     */
    @Query(value = ARTICLE_EANS + """
            select d.id as document_id, d.number as document_number, d.document_date as document_date,
                   max(l.supplier_code) as supplier_code, max(l.supplier_name) as supplier_name,
                   sum(l.quantity) as quantity, coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0
              and """ + ARTICLE_LINE + """
            group by d.id, d.number, d.document_date
            order by d.document_date desc, length(d.number) desc, d.number desc
            limit :limit
            """, nativeQuery = true)
    List<ArticleDelivery> findArticleDeliveries(UUID storeId, String itm8, int limit);

    /** Stawka VAT (%) z najnowszej linii dokumentu artykułu, która ją ma. */
    @Query(value = """
            select l.vat_rate from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and l.itm8 = :itm8 and l.vat_rate is not null
            order by d.document_date desc
            limit 1
            """, nativeQuery = true)
    Short findLatestVatRate(UUID storeId, String itm8);

    /** Protokół z artykułem: dokument z sumą ilości jego linii i tym, czy jest zarejestrowanym gratisem. */
    interface ArticleProtocol {
        UUID getDocumentId();

        String getDocumentType();

        String getDocumentNumber();

        LocalDateTime getDocumentDate();

        boolean getGratis();

        BigDecimal getQuantity();
    }

    /**
     * Ostatnie dokumenty artykułu inne niż przyjęcia i ich korekty (likwidacje, ruchy międzymagazynowe, przeniesienia
     * stanów, zwroty, wydania), od najnowszego. Ilość jak w raporcie, więc w przeniesieniu stanów ze znakiem.
     */
    @Query(value = ARTICLE_EANS + """
            select d.id as document_id, d.type as document_type, d.number as document_number,
                   d.document_date as document_date, sum(l.quantity) as quantity,
                   exists (select 1 from document_gratis g
                           where g.document_id = d.id and g.deleted_at is null) as gratis
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type not in ('PZ', 'PK', 'WZ')
              and """ + ARTICLE_LINE + """
            group by d.id, d.type, d.number, d.document_date
            having sum(l.quantity) <> 0
            order by d.document_date desc, length(d.number) desc, d.number desc
            limit :limit
            """, nativeQuery = true)
    List<ArticleProtocol> findArticleProtocols(UUID storeId, String itm8, int limit);

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

    /**
     * Linie przyjęć PZ z ilością > 0 przypisane do artykułów sklepu: po ITM8 z kartoteki, a linia z lokalnym ITM8
     * dostawcy spoza kartoteki ({@code 99…}) po EAN-ie, jak w {@link #ARTICLE_LINE}. Gdy EAN pasuje do kilku
     * artykułów, wybór ma być powtarzalny, więc bierze najmniejszy ITM8.
     */
    String DELIVERED_LINES = """
            with article_codes as (
                select ltrim(btrim(p.ean), '0') as ean, p.itm8 from products p where p.store_id = :storeId
                union
                select ltrim(btrim(pc.ean), '0'), pc.itm8 from product_codes pc
                where pc.store_id = :storeId and pc.current
                union
                select ltrim(btrim(pe.ean), '0'), p.itm8 from product_eans pe join products p on p.id = pe.product_id
                where p.store_id = :storeId
            ), article_eans as (
                select ean, min(itm8) as itm8 from article_codes where ean <> '' group by ean
            ), delivered as (
                select d.id as document_id, d.number as document_number, d.document_date, l.supplier_code,
                       l.supplier_name, l.quantity, l.purchase_value, coalesce(p.itm8, e.itm8) as itm8
                from document_lines l
                join documents d on d.id = l.document_id
                left join products p on p.store_id = :storeId and p.itm8 = l.itm8
                left join article_eans e on p.id is null and e.ean = ltrim(btrim(l.ean), '0')
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0
                  and d.document_date >= :since
            )
            """;

    /** Ostatnia dostawa artykułu. */
    interface LastDelivery {
        String getItm8();

        LocalDateTime getDocumentDate();

        String getSupplierCode();

        String getSupplierName();
    }

    /** Ostatnia dostawa każdego artykułu sklepu od {@code since} (artykułów bez przyjęcia nie ma w wyniku). */
    @Query(value = DELIVERED_LINES + """
            select distinct on (itm8) itm8, document_date, supplier_code, supplier_name
            from delivered
            where itm8 is not null
            order by itm8, document_date desc, document_number desc
            """, nativeQuery = true)
    List<LastDelivery> findLastDeliveries(UUID storeId, LocalDateTime since);

    /** Artykuł w przyjęciu: linie tego samego artykułu są zsumowane. */
    interface DeliveredArticle {
        UUID getDocumentId();

        String getDocumentNumber();

        LocalDateTime getDocumentDate();

        String getSupplierCode();

        String getSupplierName();

        String getItm8();

        BigDecimal getQuantity();

        BigDecimal getPurchaseValue();
    }

    /** Artykuły przyjęć PZ od {@code since}, od najnowszego przyjęcia; ilości i kwoty z raportu, bez korekt. */
    @Query(value = DELIVERED_LINES + """
            select document_id, document_number, document_date, max(supplier_code) as supplier_code,
                   max(supplier_name) as supplier_name, itm8, sum(quantity) as quantity,
                   coalesce(sum(purchase_value), 0) as purchase_value
            from delivered
            where itm8 is not null
            group by document_id, document_number, document_date, itm8
            order by document_date desc, length(document_number) desc, document_number desc
            """, nativeQuery = true)
    List<DeliveredArticle> findDeliveredArticles(UUID storeId, LocalDateTime since);

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
