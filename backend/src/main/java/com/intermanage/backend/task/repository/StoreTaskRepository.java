package com.intermanage.backend.task.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.task.entity.StoreTask;

public interface StoreTaskRepository extends JpaRepository<StoreTask, UUID> {

    @Query("select t from StoreTask t where t.id = :id and t.store.id = :storeId")
    Optional<StoreTask> findInStore(UUID storeId, UUID id);

    /** Zadania wykonawcy: do zrobienia, czekające na przyjęcie i przyjęte od {@code acceptedSince}. */
    @EntityGraph(attributePaths = {"assignee", "createdBy", "doneBy", "acceptedBy"})
    @Query("""
            select t from StoreTask t
            where t.store.id = :storeId and t.assignee.id = :userId and t.deletedAt is null
              and (t.status <> com.intermanage.backend.task.entity.TaskStatus.ACCEPTED or t.acceptedAt >= :acceptedSince)
            order by t.createdAt desc""")
    List<StoreTask> findOfAssignee(UUID storeId, UUID userId, Instant acceptedSince);

    /**
     * Zadania sklepu w widoku zlecającego, przyjęte od {@code acceptedSince}: właściciel wszystkie
     * ({@code creatorId} pusty), inny zlecający tylko swoje.
     */
    @EntityGraph(attributePaths = {"assignee", "createdBy", "doneBy", "acceptedBy"})
    @Query("""
            select t from StoreTask t
            where t.store.id = :storeId and t.deletedAt is null
              and (:creatorId is null or t.createdBy.id = :creatorId)
              and (t.status <> com.intermanage.backend.task.entity.TaskStatus.ACCEPTED or t.acceptedAt >= :acceptedSince)
            order by t.createdAt desc""")
    List<StoreTask> findOfStore(UUID storeId, UUID creatorId, Instant acceptedSince);

    @Query("""
            select count(t) from StoreTask t
            where t.store.id = :storeId and t.assignee.id = :userId and t.deletedAt is null
              and t.status = com.intermanage.backend.task.entity.TaskStatus.OPEN""")
    long countOpenOfAssignee(UUID storeId, UUID userId);

    @Query("""
            select count(t) from StoreTask t
            where t.store.id = :storeId and t.deletedAt is null
              and (:creatorId is null or t.createdBy.id = :creatorId)
              and t.status = com.intermanage.backend.task.entity.TaskStatus.DONE""")
    long countWaitingForAcceptance(UUID storeId, UUID creatorId);

    /** Kto może dostać zadanie w sklepie: aktywne konta przypisane do sklepu i właściciele. */
    @Query("""
            select u from AppUser u join fetch u.role
            where u.enabled = true
              and (u.role.owner = true
                   or exists (select 1 from AppUser a join a.stores s
                              where a = u and s.id = :storeId and s.active = true))
            order by lower(u.displayName)""")
    List<AppUser> findAssignees(UUID storeId);
}
