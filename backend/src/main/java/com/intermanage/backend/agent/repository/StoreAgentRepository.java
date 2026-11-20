package com.intermanage.backend.agent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.agent.entity.StoreAgent;

public interface StoreAgentRepository extends JpaRepository<StoreAgent, UUID> {

    @Query("select a from StoreAgent a join fetch a.store where a.keyHash = :keyHash")
    Optional<StoreAgent> findByKeyHash(String keyHash);

    /** Agenci sklepu: najpierw aktywni od najnowszego, usunięci na końcu (do przywrócenia). */
    @Query("""
            select a from StoreAgent a left join fetch a.createdBy
            where a.store.id = :storeId
            order by case when a.deletedAt is null then 0 else 1 end, a.createdAt desc""")
    List<StoreAgent> findByStore(UUID storeId);

    @Query("select a from StoreAgent a join fetch a.store where a.deletedAt is null")
    List<StoreAgent> findActive();

    /** Kontakt agenta: każde jego żądanie (także czekanie na pliki). */
    @Modifying
    @Query(nativeQuery = true, value = "update store_agents set last_seen_at = now() where id = :id")
    int touch(UUID id);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_agents set last_seen_at = now(), last_version = :version, machine_name = :machineName,
                machine_user = :machineUser
            where id = :id""")
    int hello(UUID id, String version, String machineName, String machineUser);
}
