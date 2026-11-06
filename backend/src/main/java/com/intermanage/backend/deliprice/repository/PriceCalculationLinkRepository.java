package com.intermanage.backend.deliprice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliprice.entity.PriceCalculationLink;

public interface PriceCalculationLinkRepository extends JpaRepository<PriceCalculationLink, UUID> {

    /** Para z automatycznego przypisania: wyrób sąsiada, artykuł sklepu, podobieństwo nazw i czy EAN jest ten sam. */
    interface Candidate {
        UUID getSourceId();

        UUID getProductId();

        double getScore();

        boolean getSameEan();
    }

    /** Aktywne przypisania sklepu do wyrobów sąsiada. */
    @Query("""
            select l from PriceCalculationLink l join fetch l.source join fetch l.product
            where l.store.id = :storeId and l.source.store.id = :partnerStoreId and l.deletedAt is null""")
    List<PriceCalculationLink> findActive(UUID storeId, UUID partnerStoreId);

    /** Pary odpięte ręcznie: automat ich nie powtarza. */
    @Query("""
            select l from PriceCalculationLink l
            where l.store.id = :storeId and l.source.store.id = :partnerStoreId and l.rejected = true""")
    List<PriceCalculationLink> findRejected(UUID storeId, UUID partnerStoreId);

    Optional<PriceCalculationLink> findByIdAndStoreId(UUID id, UUID storeId);

    /**
     * Wyroby z kalkulacji sąsiada i podobne nazwą artykuły garmażu sklepu. Sam EAN nic nie znaczy, bo kody z etykiet
     * wagi 24… i ITM8 99… są lokalne (na produkcji co czwarta para z tym samym EAN-em to inny towar). Nazwy porównujemy
     * bez polskich znaków i końcowego „LUZ”, który sklepy dopisują różnie.
     */
    @Query(value = """
            with source as (
                select p.id, p.ean, regexp_replace(upper(translate(p.name, 'ąćęłńóśźżĄĆĘŁŃÓŚŹŻ', 'acelnoszzACELNOSZZ')),
                           '\\s+LUZ\\s*$', '') as name
                from price_calculations c join products p on p.id = c.product_id
                where c.store_id = :partnerStoreId and c.deleted_at is null and p.name is not null
            ), target as (
                select p.id, p.ean, regexp_replace(upper(translate(p.name, 'ąćęłńóśźżĄĆĘŁŃÓŚŹŻ', 'acelnoszzACELNOSZZ')),
                           '\\s+LUZ\\s*$', '') as name
                from products p
                where p.store_id = :storeId and p.name is not null and p.ean is not null and p.ean <> ''
                  and left(p.nomenclature, 2) in ('24', '36', '46')
            )
            select s.id as sourceId, t.id as productId, cast(similarity(s.name, t.name) as double precision) as score,
                   ltrim(s.ean, '0') = ltrim(t.ean, '0') as sameEan
            from source s join target t on similarity(s.name, t.name) >= :minScore""", nativeQuery = true)
    List<Candidate> findCandidates(UUID storeId, UUID partnerStoreId, double minScore);
}
