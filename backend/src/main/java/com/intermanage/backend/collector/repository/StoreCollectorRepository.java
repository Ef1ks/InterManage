package com.intermanage.backend.collector.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.StoreCollector;

public interface StoreCollectorRepository extends JpaRepository<StoreCollector, UUID> {

    @Query("select c from StoreCollector c join fetch c.store where c.keyHash = :keyHash and c.deletedAt is null")
    Optional<StoreCollector> findByKeyHash(String keyHash);

    /** Kolektor z tym kodem parowania, z blokadą wiersza: kod działa raz, także przy dwóch próbach naraz. */
    @Query(nativeQuery = true, value = """
            select * from store_collectors
            where pair_code_hash = :codeHash and pair_expires_at > now() and deleted_at is null
            for update""")
    Optional<StoreCollector> findByPairCodeForUpdate(String codeHash);

    /** Kolektory sklepu: najpierw aktywne od najnowszego, usunięte na końcu (do przywrócenia). */
    @Query("""
            select c from StoreCollector c left join fetch c.createdBy
            where c.store.id = :storeId
            order by case when c.deletedAt is null then 0 else 1 end, c.createdAt desc""")
    List<StoreCollector> findByStore(UUID storeId);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_collectors
            set last_seen_at = now(), last_version = coalesce(cast(:version as varchar), last_version)
            where id = :id""")
    int touch(UUID id, String version);

    /** Kolektor zapisał u siebie kartotekę tej wersji. */
    @Modifying
    @Query(nativeQuery = true, value = """
            update store_collectors set catalog_version = :version, catalog_synced_at = now() where id = :id""")
    int synced(UUID id, String version);
}
