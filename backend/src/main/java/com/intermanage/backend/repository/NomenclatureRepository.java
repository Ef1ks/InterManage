package com.intermanage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.Nomenclature;
import com.intermanage.backend.entity.NomenclatureLevel;

public interface NomenclatureRepository extends JpaRepository<Nomenclature, UUID> {

    /** Cała nomenklatura sklepu w kolejności kodów, czyli rodzic zawsze przed dziećmi. */
    @Query("select n from Nomenclature n where n.store.id = :storeId order by n.code")
    List<Nomenclature> findAllByStore(UUID storeId);

    long countByStoreIdAndLevel(UUID storeId, NomenclatureLevel level);

    /** Blokada importu nomenklatury sklepu do końca transakcji, jak w {@link DocumentRepository#lockForImport}. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);
}
