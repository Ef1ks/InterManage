package com.intermanage.backend.cadtop.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cadtop.entity.CadStoreRegion;

public interface CadStoreRegionRepository extends JpaRepository<CadStoreRegion, UUID> {

    /** Rejon, w którym jest najwięcej sklepów. */
    @Query(value = """
            select r.region from cad_store_regions r group by r.region order by count(*) desc, r.region limit 1
            """, nativeQuery = true)
    Optional<String> findMostCommonRegion();
}
