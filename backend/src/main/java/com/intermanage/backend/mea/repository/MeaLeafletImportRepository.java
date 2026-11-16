package com.intermanage.backend.mea.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.mea.entity.MeaLeafletImport;
import com.intermanage.backend.mea.entity.MeaLeafletLine;

public interface MeaLeafletImportRepository extends JpaRepository<MeaLeafletImport, UUID> {

    Optional<MeaLeafletImport> findByYearAndWeekAndReplacedAtIsNull(short year, short week);

    List<MeaLeafletImport> findTop30ByReplacedAtIsNullOrderByYearDescWeekDesc();

    /** Ten plik z portalu (ścieżka i data zmiany) już mamy, także jako zastąpiony nowszym. */
    boolean existsBySourcePathAndSourceUpdatedAt(String sourcePath, String sourceUpdatedAt);

    /** Pozycje obowiązujących opisów gazetek o tych ITM 8 PROMO, od najnowszej gazetki. */
    @Query("""
            select l from MeaLeafletLine l join fetch l.leafletImport i
            where i.replacedAt is null and l.promoItm8 in :promoItm8s
            order by i.year desc, i.week desc, l.lp
            """)
    List<MeaLeafletLine> findActiveLines(Collection<String> promoItm8s);
}
