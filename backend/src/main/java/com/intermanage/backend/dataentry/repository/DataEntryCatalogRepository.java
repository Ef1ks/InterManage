package com.intermanage.backend.dataentry.repository;

import java.math.BigDecimal;
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

        String getItm8();

        String getName();

        String getBrand();

        String getUnit();

        BigDecimal getNetContent();

        BigDecimal getSalePrice();

        String getSubfamilyCode();
    }

    /**
     * Artykuł sklepu dla każdego EAN-u (bez zer wiodących): bieżący EAN artykułu, a gdy go nie ma, para z
     * {@code product_eans} (dawny EAN); przy kilku artykułach ten, którego to bieżący EAN, potem ostatnio zmieniany.
     */
    @Query(value = """
            with keys as (select distinct unnest(cast(:eans as varchar[])) as key),
            hits as (
                select k.key, p.id, true as current
                from keys k join products p on p.store_id = :storeId and ltrim(p.ean, '0') = k.key
                union all
                select k.key, p.id, false
                from keys k
                join product_eans pe on ltrim(pe.ean, '0') = k.key
                join products p on p.id = pe.product_id and p.store_id = :storeId
            )
            select distinct on (h.key) h.key as key, p.itm8 as itm8, p.name as name, p.brand as brand,
                p.unit as unit, p.net_content as netContent, p.sale_price as salePrice,
                p.subfamily_code as subfamilyCode
            from hits h join products p on p.id = h.id
            order by h.key, h.current desc, p.updated_at desc""", nativeQuery = true)
    List<CatalogProduct> findByEans(UUID storeId, String[] eans);

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
}
