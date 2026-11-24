package com.intermanage.backend.cadtop.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cadtop.entity.CadPlan;

public interface CadPlanRepository extends JpaRepository<CadPlan, UUID> {

    Optional<CadPlan> findByYearAndReplacedAtIsNull(short year);

    /** Ten plik już był (także zastąpiony nowszym). */
    boolean existsBySha256(String sha256);

    @Query("""
            select distinct p from CadPlan p left join fetch p.entries left join fetch p.uploadedBy
            where p.replacedAt is null order by p.year desc
            """)
    List<CadPlan> findActive();

    /** Blokada zapisu planów i plików TOP do końca transakcji (zadanie w tle i „Sprawdź portal” naraz). */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended('cad-top', 0))) l",
            nativeQuery = true)
    int lockForImport();
}
