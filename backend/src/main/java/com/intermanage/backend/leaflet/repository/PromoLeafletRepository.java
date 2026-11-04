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

    Optional<PromoLeaflet> findByStoreIdAndYearAndWeekAndReplacedAtIsNull(UUID storeId, short year, short week);

    @EntityGraph(attributePaths = "uploadedBy")
    List<PromoLeaflet> findTop30ByStoreIdAndReplacedAtIsNullOrderByUploadedAtDesc(UUID storeId);

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
     * z gazetką i najwcześniejszym początkiem promocji z tym kodem.
     */
    @Query("""
            select l.meaCode as code, p.year as year, p.week as week, min(l.dateFrom) as dateFrom
            from PromoLeafletLine l join l.leaflet p
            where p.store.id = :storeId and p.replacedAt is null and l.meaCode is not null and l.meaCode <> 'DDD'
                and l.meaCode not in (select c.code from MeaCode c where c.store.id = :storeId and c.deletedAt is null)
            group by l.meaCode, p.year, p.week
            """)
    List<MeaOccurrence> findPendingMeaCodes(UUID storeId);
}
