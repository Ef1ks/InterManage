package com.intermanage.backend.leaflet.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.leaflet.entity.PromoLeafletLine;

public interface PromoLeafletLineRepository extends JpaRepository<PromoLeafletLine, UUID> {

    interface ArticleLine {
        UUID getLineId();

        String getItm8();
    }

    /**
     * Pozycje aktywnych gazetek sklepu z każdym wzorcem w nazwie, marce, EAN-ie albo ITM8 z pliku (wielkie litery bez
     * polskich znaków), od najpóźniej kończących się.
     */
    @Query(value = """
            select x.id
            from promo_leaflet_lines x
            join promo_leaflets f on f.id = x.leaflet_id
            where f.store_id = :storeId and f.replaced_at is null
              and translate(upper(x.name || ' ' || coalesce(x.brand, '') || ' ' || x.ean || ' ' || coalesce(x.itm8, '')),
                            'ĄĆĘŁŃÓŚŹŻ', 'ACELNOSZZ') like all (cast(:patterns as text[]))
            order by x.date_to desc, x.id
            limit :limit""", nativeQuery = true)
    List<UUID> findMatching(UUID storeId, String[] patterns, int limit);

    /**
     * Pozycje aktywnych gazetek sklepu należące do artykułów, tak jak w {@link PromoLeafletRepository#ARTICLE_PERIODS}:
     * artykuł przypięty ręcznie, a bez przypięcia ITM 8 z pliku i każdy EAN artykułu (bieżący, z multikodów, dawne pary),
     * do tego zawsze ITM 8 PROMO z pliku, a towar na wagę po początku kodu z etykiety wagi.
     */
    @Query(value = """
            with wanted as (
                select p.id, p.itm8, ltrim(p.ean, '0') as ean
                from products p
                where p.store_id = :storeId and p.itm8 = any(cast(:itm8s as text[]))
            ), codes as (
                select w.itm8, w.ean from wanted w where w.ean <> ''
                union
                select c.itm8, ltrim(c.ean, '0')
                from product_codes c
                where c.store_id = :storeId and c.current and c.itm8 = any(cast(:itm8s as text[]))
                union
                select w.itm8, ltrim(e.ean, '0')
                from product_eans e join wanted w on w.id = e.product_id
            ), scales as (
                select c.itm8, left(c.ean, 7) as scale from codes c where c.ean ~ '^2[1-9][0-9]{10,11}$'
                union
                select c.itm8, left(c.ean, 6) from codes c where c.ean ~ '^2[1-9][0-9]{4}$'
            ), leaflet_lines as (
                select x.id, x.itm8, x.promo_itm8, ltrim(x.ean, '0') as ean, k.product_id as linked_product_id,
                       case when x.ean ~ '^2[1-9][0-9]{10,11}$' then left(x.ean, 7) end as scale
                from promo_leaflet_lines x
                join promo_leaflets f on f.id = x.leaflet_id
                left join promo_leaflet_links k on k.store_id = f.store_id and k.ean = ltrim(x.ean, '0')
                                               and k.deleted_at is null
                where f.store_id = :storeId and f.replaced_at is null
            )
            select x.id as lineId, w.itm8 as itm8
            from leaflet_lines x join wanted w on w.id = x.linked_product_id
            union
            select x.id, lpad(x.itm8, 13, '0')
            from leaflet_lines x
            where x.linked_product_id is null and x.itm8 ~ '^[0-9]+$'
              and lpad(x.itm8, 13, '0') = any(cast(:itm8s as text[]))
            union
            select x.id, lpad(x.promo_itm8, 13, '0')
            from leaflet_lines x
            where x.promo_itm8 ~ '^[0-9]+$' and lpad(x.promo_itm8, 13, '0') = any(cast(:itm8s as text[]))
            union
            select x.id, c.itm8
            from leaflet_lines x join codes c on c.ean = x.ean
            where x.linked_product_id is null
            union
            select x.id, s.itm8
            from leaflet_lines x join scales s on s.scale = x.scale or s.scale = left(x.scale, 6)
            where x.linked_product_id is null""", nativeQuery = true)
    List<ArticleLine> findArticleLines(UUID storeId, String[] itm8s);

    @Query("select l from PromoLeafletLine l join fetch l.leaflet where l.id in :ids")
    List<PromoLeafletLine> findWithLeaflet(Collection<UUID> ids);
}
