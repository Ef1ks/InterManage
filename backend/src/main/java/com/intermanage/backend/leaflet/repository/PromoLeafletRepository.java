package com.intermanage.backend.leaflet.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.leaflet.entity.PromoLeaflet;

public interface PromoLeafletRepository extends JpaRepository<PromoLeaflet, UUID> {

    /**
     * Artykuły z kartoteki w promocjach gazetkowych nachodzących na {@code dataFrom}-{@code dataTo}, z okresem promocji
     * (ITM8, date_from, date_to), do wstawienia w zapytanie jako podzapytanie. Pozycja wskazuje artykuł tak jak przy
     * opracowaniu gazetki: przypięty ręcznie, a bez przypięcia ITM 8 z pliku (bez zer wiodących) i każdy artykuł z tym
     * EAN-em (bieżącym, z multikodów i z dawnych par).
     */
    String ARTICLE_PERIODS = """
            with leaflet_lines as (
                select distinct x.itm8, ltrim(x.ean, '0') as ean, x.date_from, x.date_to,
                       k.product_id as linked_product_id
                from promo_leaflet_lines x
                join promo_leaflets f on f.id = x.leaflet_id
                left join promo_leaflet_links k on k.store_id = f.store_id and k.ean = ltrim(x.ean, '0')
                                               and k.deleted_at is null
                where f.store_id = :storeId and f.replaced_at is null
                  and x.date_to >= cast(:dataFrom as date) and x.date_from <= cast(:dataTo as date)
            )
            select p.itm8, x.date_from, x.date_to
            from leaflet_lines x join products p on p.id = x.linked_product_id
            union
            select lpad(x.itm8, 13, '0'), x.date_from, x.date_to
            from leaflet_lines x where x.linked_product_id is null and x.itm8 ~ '^[0-9]+$'
            union
            select p.itm8, x.date_from, x.date_to
            from leaflet_lines x join products p on p.store_id = :storeId and ltrim(p.ean, '0') = x.ean
            where x.linked_product_id is null
            union
            select c.itm8, x.date_from, x.date_to
            from leaflet_lines x join product_codes c on c.store_id = :storeId and c.current
                                                     and ltrim(c.ean, '0') = x.ean
            where x.linked_product_id is null
            union
            select p.itm8, x.date_from, x.date_to
            from leaflet_lines x
            join product_eans e on ltrim(e.ean, '0') = x.ean
            join products p on p.id = e.product_id and p.store_id = :storeId
            where x.linked_product_id is null
            """;

    Optional<PromoLeaflet> findByStoreIdAndYearAndWeekAndReplacedAtIsNull(UUID storeId, short year, short week);

    /** Po numerze gazetki, nie po dacie wgrania: gazetka sprzed miesięcy wgrana dziś nie staje się bieżącą. */
    @EntityGraph(attributePaths = "uploadedBy")
    List<PromoLeaflet> findTop60ByStoreIdAndReplacedAtIsNullOrderByYearDescWeekDesc(UUID storeId);

    /** Pierwszy dzień promocji z wgranych gazetek sklepu; wcześniej raporty nie wiedzą, co było w gazetce. */
    @Query("""
            select min(l.dateFrom) from PromoLeafletLine l join l.leaflet p
            where p.store.id = :storeId and p.replacedAt is null""")
    LocalDate findFirstPromotionDate(UUID storeId);

    @EntityGraph(attributePaths = { "uploadedBy", "lines" })
    Optional<PromoLeaflet> findByIdAndStoreIdAndReplacedAtIsNull(UUID id, UUID storeId);

    interface MeaOccurrence {
        String getCode();

        int getYear();

        int getWeek();

        LocalDate getDateFrom();
    }

    /**
     * Kody MEA z aktywnych gazetek sklepu (bez DDD, które nie jest kodem), których nie potwierdzono jako wpisane,
     * z gazetką i najwcześniejszym początkiem promocji z tym kodem. Kod promocji zakończonych przed {@code today} nie
     * jest już do wpisania (gazetki wgrywane wstecz do raportów nie zapełniają listy).
     */
    @Query("""
            select l.meaCode as code, p.year as year, p.week as week, min(l.dateFrom) as dateFrom
            from PromoLeafletLine l join l.leaflet p
            where p.store.id = :storeId and p.replacedAt is null and l.meaCode is not null and l.meaCode <> 'DDD'
                and l.meaCode not in (select c.code from MeaCode c where c.store.id = :storeId and c.deletedAt is null)
            group by l.meaCode, p.year, p.week
            having max(l.dateTo) >= :today
            """)
    List<MeaOccurrence> findPendingMeaCodes(UUID storeId, LocalDate today);
}
