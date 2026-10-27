package com.intermanage.backend.supplier.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.supplier.entity.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    /** Dostawcy z raportu (z kodem), do scalenia z nowym plikiem. */
    @Query("select s from Supplier s where s.store.id = :storeId and s.code is not null")
    List<Supplier> findImported(UUID storeId);

    @Query("select s from Supplier s where s.store.id = :storeId order by lower(s.name), s.code")
    List<Supplier> findByStore(UUID storeId);

    @Query("select s from Supplier s where s.id = :id and s.store.id = :storeId")
    Optional<Supplier> findInStore(UUID storeId, UUID id);

    @Query("select count(s) > 0 from Supplier s where s.store.id = :storeId and s.code is null and lower(s.name) = lower(:name)")
    boolean existsManualByName(UUID storeId, String name);

    long countByStoreId(UUID storeId);

    @Query("select count(s) from Supplier s where s.store.id = :storeId and s.code is null")
    long countManual(UUID storeId);

    /** Blokada importu listy dostawców sklepu do końca transakcji, jak w {@link DocumentRepository#lockForImport}. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);
}
