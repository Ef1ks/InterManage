package com.intermanage.backend.bakery.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.bakery.entity.BakeryWz;

public interface BakeryWzRepository extends JpaRepository<BakeryWz, UUID> {

    /** Dokumenty WZ weryfikacji (bez usuniętych) razem z ilościami, w kolejności dodania. */
    @Query("""
            select distinct w from BakeryWz w left join fetch w.lines l left join fetch l.product left join fetch w.scan
            left join fetch w.sourceInvoice where w.check.id in :checkIds and w.deletedAt is null order by w.position""")
    List<BakeryWz> findWithLines(Collection<UUID> checkIds);

    @Query("select coalesce(max(w.position), 0) from BakeryWz w where w.check.id = :checkId")
    int lastPosition(UUID checkId);

    /** Także usuniętych: usunięty WZ nie wraca przy ponownym odczycie z faktury. */
    @Query("select w.number from BakeryWz w where w.check.id = :checkId and w.number is not null")
    List<String> findNumbers(UUID checkId);

    @Query("select w from BakeryWz w where w.id = :id and w.check.id = :checkId")
    Optional<BakeryWz> findInCheck(UUID checkId, UUID id);
}
