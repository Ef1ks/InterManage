package com.intermanage.backend.agent.repository;

import java.time.Instant;
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

    /** Agent preferowany (odbiera rolę głównego agenta sklepu pozostałym). */
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = "update store_agents set preferred = :preferred where id = :id")
    int setPreferred(UUID id, boolean preferred);

    /**
     * Dzierżawa głównego agenta sklepu jednym zapytaniem (dwa agenty naraz jej nie dostaną): główny ją przedłuża,
     * a inny agent przejmuje, gdy wygasła, główny został usunięty albo przejmujący jest preferowany, a główny nie.
     * Zwraca id agenta, gdy dzierżawa jest jego; pusta lista = główny jest inny. Bez {@code @Modifying}, bo
     * {@code returning} zwraca wiersze jak zapytanie.
     */
    @Query(nativeQuery = true, value = """
            insert into store_agent_leaders as l (store_id, agent_id, since, expires_at)
            values (:storeId, :agentId, now(), now() + make_interval(secs => :seconds))
            on conflict (store_id) do update
            set agent_id = excluded.agent_id,
                since = case when l.agent_id = excluded.agent_id then l.since else now() end,
                expires_at = excluded.expires_at
            where l.agent_id = excluded.agent_id
               or l.expires_at < now()
               or exists (select 1 from store_agents d where d.id = l.agent_id and d.deleted_at is not null)
               or ((select a.preferred from store_agents a where a.id = excluded.agent_id)
                   and not (select c.preferred from store_agents c where c.id = l.agent_id))
            returning agent_id""")
    List<UUID> claimLeader(UUID storeId, UUID agentId, int seconds);

    /** Główny agent sklepu z ważną dzierżawą. */
    @Query(nativeQuery = true, value = """
            select agent_id from store_agent_leaders where store_id = :storeId and expires_at > now()""")
    Optional<UUID> findLeader(UUID storeId);

    /** Agent oddaje dzierżawę (np. przestał być preferowany albo został usunięty). */
    @Modifying
    @Query(nativeQuery = true, value = """
            update store_agent_leaders set expires_at = now() - interval '1 second'
            where store_id = :storeId and agent_id = :agentId""")
    int releaseLeader(UUID storeId, UUID agentId);

    /**
     * Początek aktualizacji danych z Mercalys: tylko gdy żaden inny agent sklepu jej nie prowadzi (blokada wygasa po
     * 4 godzinach, gdy agent nie zgłosił końca; sam Excel dokumentów ma godzinę). 0 = aktualizuje inny komputer.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            update store_agent_leaders
            set update_agent_id = :agentId, update_started_at = now()
            where store_id = :storeId
              and (update_agent_id is null or update_agent_id = :agentId
                   or update_started_at < now() - interval '4 hours')""")
    int startUpdate(UUID storeId, UUID agentId);

    /** Czy któryś komputer sklepu prowadzi teraz aktualizację danych z Mercalys. */
    @Query(nativeQuery = true, value = """
            select exists (select 1 from store_agent_leaders
                           where store_id = :storeId and update_agent_id is not null
                             and update_started_at >= now() - interval '4 hours')""")
    boolean isUpdating(UUID storeId);

    interface RunningUpdate {
        String getName();

        String getMachineName();

        Instant getStartedAt();
    }

    /** Komputer, który prowadzi teraz aktualizację danych (zgłasza się; zabity w trakcie jej już nie prowadzi). */
    @Query(nativeQuery = true, value = """
            select a.name as "name", a.machine_name as "machineName", l.update_started_at as "startedAt"
            from store_agent_leaders l
            join store_agents a on a.id = l.update_agent_id and a.deleted_at is null
            where l.store_id = :storeId and l.update_started_at >= now() - interval '4 hours'
              and a.last_seen_at > now() - interval '3 minutes'""")
    Optional<RunningUpdate> findRunningUpdate(UUID storeId);

    @Modifying
    @Query(nativeQuery = true, value = """
            update store_agent_leaders set update_agent_id = null
            where store_id = :storeId and update_agent_id = :agentId""")
    int finishUpdate(UUID storeId, UUID agentId);
}
