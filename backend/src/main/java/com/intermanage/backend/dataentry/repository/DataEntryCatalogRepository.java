package com.intermanage.backend.dataentry.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.entity.Product;

/**
 * Kartoteka sklepu pod wprowadzanie danych: znane artykuły po EAN-ie, podobne nazwy (pg_trgm), inne produkty
 * producenta (prefiks EAN), artykuły dostawcy i istniejące zapisy marek. EAN-y porównujemy bez zer wiodących.
 */
public interface DataEntryCatalogRepository extends Repository<Product, UUID> {

    interface CatalogProduct {
        String getKey();

        boolean getByItm8();

        String getItm8();

        String getCatalogEan();

        String getName();

        String getBrand();

        BigDecimal getPurchasePrice();

        BigDecimal getVatPercent();

        BigDecimal getCatalogVat();

        String getUnit();

        BigDecimal getNetContent();

        BigDecimal getSalePrice();

        boolean getCadencier();

        BigDecimal getCadencierPrice();

        BigDecimal getPromoPrice();

        LocalDate getPromoTo();

        String getSubfamilyCode();

        BigDecimal getStock();

        BigDecimal getRotation();
    }

    /**
     * Artykuł sklepu dla każdego EAN-u (bez zer wiodących): bieżący EAN artykułu, potem każdy obowiązujący kod
     * z multikodów ({@code product_codes}), a gdy go nie ma, para z {@code product_eans} (dawny EAN); przy kilku
     * artykułach ten z lepszego źródła, potem ostatnio zmieniany.
     * {@code cadencier}: artykuł ma CENA_CAD z ostatniego raportu (0 = brak) i jest aktywny w cadencier, czyli na
     * bieżącej liście {@code cadencier_items} sklepu (po ITM8).
     * Kod z etykiety wagi sklepu (2 + cyfra 1-9, 12-13 cyfr, w cyfrach 8-12 cena albo waga) trafia w artykuł, którego
     * EAN ma te same pierwsze 7 cyfr i zera w miejscu ceny ({@code 2404140000000}), w kartotece albo w multikodach
     * (towar na wagę z krótkim EAN-em w kartotece: IMBIR LUZ {@code 0000000219285}, multikod {@code 2192850000000},
     * w dostawie z bazy {@code 2192850000007}); dokładny EAN ma pierwszeństwo.
     * Z {@code byItm8} (kod wpisany ręcznie) kod, który nie jest żadnym EAN-em, szuka jeszcze artykułu po ITM8, a potem
     * po ITM8 z multikodów (artykuł z EAN-em tej pary); {@code byItm8} w wyniku mówi, że trafił ITM8.
     * {@code vatPercent}: z kodu stawki z kartoteki (1 = 23%, 2 = 8%, 6 = 5%, sprawdzone z liniami dokumentów; inne
     * kody, np. 3, 7 kaucje, są niepewne), bez niego z najnowszej linii dokumentu sklepu z tym ITM8, a na końcu
     * najczęstsza stawka z dokumentów artykułów tej samej podrodziny. {@code catalogVat}: to samo bez najczęstszej
     * stawki, bo to tylko podpowiedź (w podrodzinie SEZON 23% od pralin, biszkopty mają 5%), a nie stawka artykułu.
     * {@code promoPrice}/{@code promoTo}: trwająca w dniu {@code today} promocja z pliku TRACEART; jej cena jest wtedy
     * też {@code salePrice} (aktualna cena na kasie). Bez niej trwająca promocja z listy promocji, ale tylko gdy jej
     * cena to cena sprzedaży z kartoteki: plik TRACEART sprzed ostatniego raportu kartoteki jest pomijany, więc
     * promocja rozpoczęta przed raportem ma w kartotece samą cenę promocyjną, bez daty końca.
     */
    @Query(value = """
            with keys as (select distinct unnest(cast(:eans as varchar[])) as key),
            hits as (
                select k.key, p.id, 0 as rank
                from keys k join products p on p.store_id = :storeId and ltrim(p.ean, '0') = k.key
                union all
                select k.key, p.id, 1
                from keys k
                join product_codes c on c.store_id = :storeId and c.current and ltrim(c.ean, '0') = k.key
                join products p on p.store_id = :storeId and p.itm8 = c.itm8
                union all
                select k.key, p.id, 2
                from keys k
                join product_eans pe on ltrim(pe.ean, '0') = k.key
                join products p on p.id = pe.product_id and p.store_id = :storeId
                union all
                select k.key, p.id, 3
                from keys k
                join products p on p.store_id = :storeId and left(p.ean, 7) = left(k.key, 7)
                    and p.ean ~ '^2[1-9][0-9]{5}00000[0-9]$'
                where k.key ~ '^2[1-9][0-9]{10,11}$'
                union all
                select k.key, p.id, 3
                from keys k
                join product_codes c on c.store_id = :storeId and c.current and left(c.ean, 7) = left(k.key, 7)
                    and c.ean ~ '^2[1-9][0-9]{5}00000[0-9]$'
                join products p on p.store_id = :storeId and p.itm8 = c.itm8
                where k.key ~ '^2[1-9][0-9]{10,11}$'
                union all
                select k.key, p.id, 4
                from keys k join products p on p.store_id = :storeId and p.itm8 = lpad(k.key, 13, '0')
                where :byItm8 and length(k.key) <= 13
                union all
                select k.key, p.id, 5
                from keys k
                join product_codes c on c.store_id = :storeId and c.current and c.itm8 = lpad(k.key, 13, '0')
                join products p on p.store_id = :storeId and ltrim(p.ean, '0') = ltrim(c.ean, '0')
                where :byItm8 and length(k.key) <= 13
            )
            select distinct on (h.key) h.key as key, h.rank >= 4 as byItm8, p.itm8 as itm8, p.ean as catalogEan,
                p.name as name,
                p.brand as brand, p.purchase_price as purchasePrice,
                coalesce(
                    v.rate,
                    (select mode() within group (order by l.vat_rate)
                     from products q
                     join document_lines l on l.itm8 = q.itm8
                     join documents d on d.id = l.document_id and d.error_marked_at is null and d.store_id = :storeId
                     where q.store_id = :storeId and q.subfamily_code = p.subfamily_code
                         and l.vat_rate is not null)) as vatPercent,
                v.rate as catalogVat,
                p.unit as unit, p.net_content as netContent,
                case when p.promo_price is not null and p.promo_to >= cast(:today as date)
                    and (p.promo_from is null or p.promo_from <= cast(:today as date)) then p.promo_price
                    else p.sale_price end as salePrice,
                coalesce(p.cadencier_price > 0 and c.itm8 is not null, false) as cadencier,
                case when p.cadencier_price > 0 and c.itm8 is not null then p.cadencier_price end as cadencierPrice,
                coalesce(case when p.promo_price is not null and p.promo_to >= cast(:today as date)
                    and (p.promo_from is null or p.promo_from <= cast(:today as date)) then p.promo_price end,
                    lp.promo_price) as promoPrice,
                coalesce(case when p.promo_price is not null and p.promo_to >= cast(:today as date)
                    and (p.promo_from is null or p.promo_from <= cast(:today as date)) then p.promo_to end,
                    lp.end_date) as promoTo,
                p.subfamily_code as subfamilyCode, p.stock as stock, p.rotation as rotation
            from hits h join products p on p.id = h.id
            left join cadencier_items c on c.store_id = p.store_id and c.itm8 = p.itm8
            cross join lateral (select coalesce(
                case p.vat_code when 1 then 23 when 2 then 8 when 6 then 5 end,
                (select l.vat_rate from document_lines l join documents d on d.id = l.document_id and d.error_marked_at is null
                 where d.store_id = :storeId and l.itm8 = p.itm8 and l.vat_rate is not null
                 order by d.document_date desc limit 1)) as rate) v
            left join lateral (
                select pr.promo_price, pr.end_date from promotions pr
                where pr.store_id = p.store_id and pr.itm8 = p.itm8 and pr.promo_price = p.sale_price
                    and pr.start_date <= cast(:today as date) and pr.end_date >= cast(:today as date)
                order by pr.end_date desc limit 1) lp on true
            order by h.key, h.rank, p.updated_at desc""", nativeQuery = true)
    List<CatalogProduct> findByEans(UUID storeId, String[] eans, boolean byItm8, LocalDate today);

    interface CatalogListItem {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSupplier();

        String getSubfamilyCode();

        String getSubfamilyName();

        BigDecimal getPurchasePrice();

        BigDecimal getSalePrice();

        BigDecimal getStock();

        BigDecimal getRotation();

        /** Tylko w liście towarów: oczekujący szkic artykułu. */
        boolean getDraft();
    }

    /**
     * Lista towarów (okno „Lista towarów”): artykuły sklepu, które pasują do każdego podanego filtra ({@code null} =
     * bez filtra, pusta tablica = bez filtra). Każde pole to tablica wzorców LIKE (słowa filtra), które muszą pasować
     * wszystkie. Pole „Szukaj” to trzy tablice po jednym wzorcu na słowo: {@code queryText} (nazwa albo marka),
     * {@code queryCode} (EAN albo ITM8) i {@code queryMultiCode} (EAN z multikodów, {@code null} dla słowa bez cyfr,
     * żeby nie szukać w multikodach przy każdym artykule); słowo musi trafić w któreś z nich. Wzorce: nazwa, marka i dostawca bez wielkości liter i polskich znaków (wzorce przychodzą już
     * wielkimi literami bez znaków diakrytycznych), ITM8 i EAN jako tekst (EAN: główny albo każdy obowiązujący
     * z multikodów). Dostawca to nie pole z kartoteki (często błędne), tylko przyjęcia PZ od {@code deliveredSince}
     * z ilością > 0: dostawca linii o kodzie równym {@code supplierCode} albo nazwie pasującej do {@code supplier}
     * (indeks {@code documents_store_type_date_idx}, potem linie po dokumencie). Nomenklatura: {@code nomenclatureCode} to
     * początek kodu podrodziny, {@code nomenclatureName} wzorzec nazwy rejonu, rodziny albo podrodziny artykułu.
     * Ceny w przedziałach z granicami włącznie. Sortowanie po kolumnie {@code sort} (itm8, ean, name, brand,
     * nomenclature, purchase, sale, stock, rotation) w kierunku {@code direction} (asc/desc), puste na końcu, potem po nazwie;
     * strona {@code limit}. Razem z kartoteką oczekujące szkice artykułów z CADBEZP ({@code draft}, bez ITM8, stanu
     * i rotacji), bo są już w programie sklepu; filtr ITM8 i dostawcy z przyjęć ich nie obejmuje.
     */
    @Query(value = """
            select p.itm8 as itm8, p.ean as ean, p.name as name, p.brand as brand, p.supplier as supplier,
                p.subfamily_code as subfamilyCode, s.name as subfamilyName,
                p.purchase_price as purchasePrice, p.sale_price as salePrice, p.stock as stock,
                p.rotation as rotation, p.draft as draft
            from (
                select a.store_id, a.itm8, a.ean, a.name, a.brand, a.supplier, a.subfamily_code, a.region_code,
                    a.family_code, a.purchase_price, a.sale_price, a.stock, a.rotation, false as draft
                from products a
                union all
                select b.store_id, null, b.ean, b.name, b.brand, b.supplier_name, b.subfamily_code,
                    left(b.subfamily_code, 2), left(b.subfamily_code, 4), b.purchase_price, b.sale_price, null, null,
                    true
                from product_drafts b
                where b.matched_at is null and b.removed_at is null
            ) p
            left join nomenclatures s on s.store_id = p.store_id and s.code = p.subfamily_code
            where p.store_id = :storeId
                and not exists (
                    select 1 from unnest(cast(:queryText as text[]), cast(:queryCode as text[]),
                        cast(:queryMultiCode as text[])) w(t, c, m)
                    where not (translate(upper(coalesce(p.name, '')), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like w.t
                        or translate(upper(coalesce(p.brand, '')), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like w.t
                        or coalesce(p.ean, '') like w.c or ltrim(coalesce(p.ean, ''), '0') like w.c
                        or coalesce(p.itm8, '') like w.c
                        or (w.m is not null and exists (
                            select 1 from product_codes pc
                            where pc.store_id = :storeId and pc.current and pc.itm8 = p.itm8
                                and (pc.ean like w.m or ltrim(pc.ean, '0') like w.m)))))
                and (cardinality(cast(:itm8 as text[])) = 0 or p.itm8 like all (cast(:itm8 as text[])))
                and (cardinality(cast(:ean as text[])) = 0 or p.ean like all (cast(:ean as text[]))
                    or ltrim(p.ean, '0') like all (cast(:ean as text[]))
                    or p.itm8 in (select c.itm8 from product_codes c
                                  where c.store_id = :storeId and c.current
                                      and (c.ean like all (cast(:ean as text[]))
                                          or ltrim(c.ean, '0') like all (cast(:ean as text[])))))
                and (cardinality(cast(:name as text[])) = 0
                    or translate(upper(p.name), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like all (cast(:name as text[])))
                and (cardinality(cast(:brand as text[])) = 0
                    or translate(upper(p.brand), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like all (cast(:brand as text[])))
                and (cardinality(cast(:supplier as text[])) = 0 or p.itm8 in (
                    select l.itm8 from documents d join document_lines l on l.document_id = d.id and d.error_marked_at is null
                    where d.store_id = :storeId and d.type = 'PZ' and d.document_date >= cast(:deliveredSince as timestamp)
                        and l.quantity > 0
                        and (l.supplier_code = cast(:supplierCode as text)
                            or translate(upper(l.supplier_name), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like all (cast(:supplier as text[])))))
                and (cast(:nomenclatureCode as text) is null
                    or p.subfamily_code like cast(:nomenclatureCode as text) || '%')
                and (cardinality(cast(:nomenclatureName as text[])) = 0 or exists (
                    select 1 from nomenclatures n
                    where n.store_id = p.store_id and n.code in (p.region_code, p.family_code, p.subfamily_code)
                        and translate(upper(n.name), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like all (cast(:nomenclatureName as text[]))))
                and (cast(:purchaseFrom as numeric) is null or p.purchase_price >= cast(:purchaseFrom as numeric))
                and (cast(:purchaseTo as numeric) is null or p.purchase_price <= cast(:purchaseTo as numeric))
                and (cast(:saleFrom as numeric) is null or p.sale_price >= cast(:saleFrom as numeric))
                and (cast(:saleTo as numeric) is null or p.sale_price <= cast(:saleTo as numeric))
            order by
                case when cast(:direction as text) = 'asc' then case cast(:sort as text)
                    when 'itm8' then p.itm8 when 'ean' then p.ean when 'name' then p.name when 'brand' then p.brand
                    when 'nomenclature' then p.subfamily_code end end asc nulls last,
                case when cast(:direction as text) = 'desc' then case cast(:sort as text)
                    when 'itm8' then p.itm8 when 'ean' then p.ean when 'name' then p.name when 'brand' then p.brand
                    when 'nomenclature' then p.subfamily_code end end desc nulls last,
                case when cast(:direction as text) = 'asc' then case cast(:sort as text)
                    when 'purchase' then p.purchase_price when 'sale' then p.sale_price when 'stock' then p.stock
                    when 'rotation' then p.rotation end end asc nulls last,
                case when cast(:direction as text) = 'desc' then case cast(:sort as text)
                    when 'purchase' then p.purchase_price when 'sale' then p.sale_price when 'stock' then p.stock
                    when 'rotation' then p.rotation end end desc nulls last,
                p.name, p.itm8
            limit :limit offset :offset""", nativeQuery = true)
    List<CatalogListItem> search(UUID storeId, String[] queryText, String[] queryCode, String[] queryMultiCode,
            String[] itm8, String[] ean, String[] name, String[] brand,
            String[] supplier, String supplierCode, LocalDate deliveredSince, String nomenclatureCode,
            String[] nomenclatureName, BigDecimal purchaseFrom, BigDecimal purchaseTo,
            BigDecimal saleFrom, BigDecimal saleTo, String sort, String direction, int limit, int offset);

    /**
     * CTE {@code article_lines}: linie dokumentów artykułów {@code :itm8s} ({@code article} = ITM8 z kartoteki). Linia
     * z lokalnym ITM8 dostawcy, którego nie ma w kartotece, należy do artykułu po EAN-ie.
     */
    String ARTICLE_LINES = """
            article_eans as (
                select p.itm8, ltrim(btrim(p.ean), '0') as ean from products p
                where p.store_id = :storeId and p.itm8 in (:itm8s)
                union
                select p.itm8, ltrim(btrim(pe.ean), '0') from product_eans pe join products p on p.id = pe.product_id
                where p.store_id = :storeId and p.itm8 in (:itm8s)
                union
                select pc.itm8, ltrim(btrim(pc.ean), '0') from product_codes pc
                where pc.store_id = :storeId and pc.current and pc.itm8 in (:itm8s)
            ), article_lines as (
                select l.itm8 as article, l.id from document_lines l where l.itm8 in (:itm8s)
                union
                select e.itm8, l.id from article_eans e
                join document_lines l on ltrim(btrim(l.ean), '0') = e.ean
                where e.ean <> ''
                    and not exists (select 1 from products o where o.store_id = :storeId and o.itm8 = l.itm8)
            )
            """;

    interface LastDelivery {
        String getItm8();

        String getSupplierCode();

        String getSupplierName();

        LocalDateTime getDocumentDate();
    }

    /**
     * Ostatnie przyjęcie PZ z ilością > 0 każdego z {@code itm8s} (dostawca linii i data dokumentu), do kolumny
     * „Ostatnia dostawa” listy towarów; liczone tylko dla strony listy. Linia z lokalnym ITM8 dostawcy, którego nie ma
     * w kartotece, należy do artykułu po EAN-ie (jak {@code DocumentLineRepository.findArticleDeliveries}).
     */
    @Query(value = "with " + ARTICLE_LINES + """
            select distinct on (a.article) a.article as itm8, l.supplier_code as supplierCode,
                l.supplier_name as supplierName, d.document_date as documentDate
            from article_lines a
            join document_lines l on l.id = a.id
            join documents d on d.id = l.document_id and d.error_marked_at is null
            where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0
            order by a.article, d.document_date desc, d.number desc""", nativeQuery = true)
    List<LastDelivery> findLastDeliveries(UUID storeId, Collection<String> itm8s);

    /**
     * Propozycje artykułów do przypięcia pozycji faktury bez EAN-u ({@code text}: nazwa po normalizacji, wielkie
     * litery bez polskich znaków). Kandydat ma nazwę podobną w pg_trgm ({@code <%} z progiem z
     * {@link #useSimilarityThreshold()} albo {@code %}) albo słowa zaczynające się od co najmniej {@code needed}
     * z {@code prefixes} (pierwsze litery słów z faktury: kartoteka skraca, "SZYNKA KRUCHA" to "SZY KRU", i pomija
     * słowa). Kolejność: połowa podobieństwa trigramów, połowa udziału trafionych początków słów, premia dla towaru
     * dostawcy o tym NIP-ie (nazwa z listy dostawców). Tylko artykuły z EAN-em.
     * Kandydatów zawęża najpierw indeks {@code products_name_trgm}: nazwa zawiera któryś początek słowa
     * ({@code candidatePattern}, np. {@code ([CĆ][EĘ]B|[CĆ][ZŹŻ][EĘ])}, bez wielkości liter) albo jest podobna
     * ({@code %}); ocena liczy się tylko dla nich. Bez zawężenia zapytanie liczyło podobieństwo i wyrażenia regularne
     * dla całej kartoteki (ok. 72 tys. artykułów, ok. 1 s; z zawężeniem ok. 45 ms). JIT tu nie pomaga: koszt planu
     * jest poniżej {@code jit_above_cost}.
     */
    @Query(value = """
            with q as (select cast(:text as text) as t),
            candidates as (
                select p.id, p.name, p.brand
                from products p cross join q
                where p.store_id = :storeId and p.ean <> ''
                    and (p.name ~* cast(:candidatePattern as text) or p.name % q.t)
            ),
            scored as (
                select c.id, word_similarity(c.name, q.t) as sim,
                    (select count(*) from unnest(cast(:prefixes as text[])) x
                     where translate(upper(c.name || ' ' || coalesce(c.brand, '')), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ')
                        ~ ('\\m' || x)) as hits
                from candidates c cross join q
            )
            select p.itm8 as itm8, p.ean as ean, p.name as name, p.brand as brand, p.supplier as supplier,
                p.subfamily_code as subfamilyCode, n.name as subfamilyName,
                p.purchase_price as purchasePrice, p.sale_price as salePrice, p.stock as stock,
                p.rotation as rotation
            from scored sc
            join products p on p.id = sc.id
            cross join q
            left join nomenclatures n on n.store_id = p.store_id and n.code = p.subfamily_code
            where sc.hits >= :needed or p.name <% q.t or p.name % q.t
            order by sc.sim * 0.5 + sc.hits * 0.5 / greatest(cardinality(cast(:prefixes as text[])), 1)
                + case when p.supplier in (
                    select s.name from suppliers s
                    where s.store_id = :storeId and regexp_replace(coalesce(s.nip, ''), '\\D', '', 'g') = :nip)
                  then 0.15 else 0 end desc,
                p.name
            limit :limit""", nativeQuery = true)
    List<CatalogListItem> findLinkCandidates(UUID storeId, String text, String[] prefixes, String candidatePattern,
            int needed, String nip, int limit);

    /**
     * JIT wyłączony do końca transakcji: przy wielu EAN-ach szacowany koszt {@link #findByEans} przekracza
     * {@code jit_above_cost}, a kompilacja (ok. 0,9 s) trwa dłużej niż samo zapytanie.
     */
    @Query(value = "select set_config('jit', 'off', true)", nativeQuery = true)
    String disableJit();

    /**
     * Próg {@code <%} (word_similarity) do końca transakcji; domyślne 0,6 odrzuca za dużo, w teście dobre było 0,3.
     */
    @Query(value = "select set_config('pg_trgm.word_similarity_threshold', '0.3', true)", nativeQuery = true)
    String useSimilarityThreshold();

    interface SimilarProduct {
        int getIdx();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        double getSimilarity();
    }

    /**
     * Do {@code limit} artykułów z nomenklaturą, których nazwa najbardziej przypomina fragment każdej nazwy z faktury
     * (indeks GIN {@code products_name_trgm}); {@code idx} to pozycja nazwy w tablicy, od 1.
     */
    @Query(value = """
            select q.idx as idx, s.name as name, s.brand as brand, s.subfamily_code as subfamilyCode,
                s.sim as similarity
            from unnest(cast(:names as text[])) with ordinality as q(txt, idx)
            cross join lateral (
                select p.name, p.brand, p.subfamily_code, word_similarity(p.name, q.txt) as sim
                from products p
                where p.name <% q.txt and p.store_id = :storeId and p.subfamily_code is not null
                order by p.name <<-> q.txt
                limit :limit
            ) s
            order by q.idx, s.sim desc""", nativeQuery = true)
    List<SimilarProduct> findSimilar(UUID storeId, String[] names, int limit);

    interface ProducerProduct {
        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();
    }

    /** Artykuły z nomenklaturą, których EAN-13 (bez zer wiodących, nie z puli sklepowej 2…) zaczyna się od prefiksu. */
    @Query(value = """
            select ltrim(p.ean, '0') as ean, p.name as name, p.brand as brand, p.subfamily_code as subfamilyCode
            from products p
            where p.store_id = :storeId and p.subfamily_code is not null and length(ltrim(p.ean, '0')) = 13
                and left(ltrim(p.ean, '0'), 7) = any(cast(:prefixes as varchar[]))""", nativeQuery = true)
    List<ProducerProduct> findByEanPrefixes(UUID storeId, String[] prefixes);

    interface CodeCount {
        String getCode();

        long getCount();
    }

    /**
     * Podrodziny artykułów dostawcy o tym NIP-ie: NIP z listy dostawców (bez kresek), artykuł po nazwie dostawcy
     * (w kartotece dostawca to nazwa z tej listy).
     */
    @Query(value = """
            select p.subfamily_code as code, count(*) as count
            from products p
            where p.store_id = :storeId and p.subfamily_code is not null and p.supplier in (
                select s.name from suppliers s
                where s.store_id = :storeId and regexp_replace(coalesce(s.nip, ''), '\\D', '', 'g') = :nip)
            group by p.subfamily_code""", nativeQuery = true)
    List<CodeCount> countSubfamiliesOfSupplier(UUID storeId, String nip);

    /** Zapisy marek z kartoteki, które po zdjęciu polskich znaków i wielkości liter są równe którejś z podanych. */
    @Query(value = """
            select p.brand as code, count(*) as count
            from products p
            where p.store_id = :storeId and p.brand is not null
                and translate(upper(p.brand), 'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') = any(cast(:brands as varchar[]))
            group by p.brand""", nativeQuery = true)
    List<CodeCount> countBrands(UUID storeId, String[] brands);

    /**
     * Zajęte kody artykułów z etykiety wagi z prefiksem 24 (pierwsze 6 cyfr, np. {@code 240029} z
     * {@code 2400290000000}): bieżące EAN-y kartoteki, multikody (też dawne), dawne pary {@code product_eans}
     * i oczekujące szkice artykułów z CADBEZP. EAN-y bez zer wiodących.
     */
    @Query(value = """
            with eans as (
                select p.ean from products p where p.store_id = :storeId
                union all
                select c.ean from product_codes c where c.store_id = :storeId
                union all
                select pe.ean from product_eans pe join products p on p.id = pe.product_id and p.store_id = :storeId
                union all
                select d.ean from product_drafts d
                where d.store_id = :storeId and d.matched_at is null and d.removed_at is null
            )
            select distinct left(ltrim(ean, '0'), 6) from eans
            where ltrim(ean, '0') ~ '^24[0-9]{10,11}$'""", nativeQuery = true)
    List<String> findUsedScaleCodes(UUID storeId);
}
