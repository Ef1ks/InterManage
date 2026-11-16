package com.intermanage.backend.mea.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.mea.entity.MeaAthosImport;

public interface MeaAthosImportRepository extends JpaRepository<MeaAthosImport, UUID> {

    @EntityGraph(attributePaths = "importedBy")
    Optional<MeaAthosImport> findFirstByStoreIdOrderByImportedAtDesc(UUID storeId);

    boolean existsByStoreId(UUID storeId);

    /** Najnowsza wysyłka każdego MEA w sklepie. */
    interface LatestSend {
        String getNumber();

        LocalDateTime getSentAt();

        int getPositions();

        int getColis();
    }

    @Query(value = """
            select distinct on (number) number, sent_at, positions, colis
            from mea_athos_sends
            where store_id = :storeId
            order by number, sent_at desc
            """, nativeQuery = true)
    List<LatestSend> findLatestSends(UUID storeId);

    /** Wszystkie wysyłki jednego MEA w sklepie, od najnowszej. */
    @Query(value = """
            select number, sent_at, positions, colis
            from mea_athos_sends
            where store_id = :storeId and number = :number
            order by sent_at desc
            """, nativeQuery = true)
    List<LatestSend> findSends(UUID storeId, String number);

    @Query(value = "select count(*) from mea_athos_sends where store_id = :storeId", nativeQuery = true)
    long countSends(UUID storeId);

    /**
     * Dopisuje wysyłki z dziennika jednym zapytaniem; wpis, który już jest (ten sam sklep, MEA i chwila wysyłki),
     * pomija. Zwraca liczbę nowych.
     */
    @Modifying
    @Query(value = """
            insert into mea_athos_sends (store_id, number, sent_at, positions, colis)
            select :storeId, s.number, s.sent_at, s.positions, s.colis
            from unnest(cast(:numbers as varchar[]), cast(:sentAts as timestamp[]),
                        cast(:positions as integer[]), cast(:colis as integer[]))
                 as s(number, sent_at, positions, colis)
            on conflict (store_id, number, sent_at) do nothing
            """, nativeQuery = true)
    int insertSends(UUID storeId, String[] numbers, LocalDateTime[] sentAts, Integer[] positions, Integer[] colis);
}
