package com.intermanage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.KsefConnection;

/** Połączenia sklepów z KSeF; kluczem jest id sklepu. Sklepy jednej firmy mają połączenia z tym samym NIP-em. */
public interface KsefConnectionRepository extends JpaRepository<KsefConnection, UUID> {

    /** Połączenia firmy, najpierw te z udanym testem logowania i najświeższe. */
    @Query("""
            select c from KsefConnection c where c.nip = :nip
            order by case when c.testOk = true then 0 else 1 end, c.updatedAt desc""")
    List<KsefConnection> findByNip(String nip);

    /** Sklepy firmy: z połączeniem z tym NIP-em. */
    @Query("select c.storeId from KsefConnection c where c.nip = :nip")
    List<UUID> findStoreIdsByNip(String nip);
}
