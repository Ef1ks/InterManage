package com.intermanage.backend.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.UserStoreRole;

public interface UserStoreRoleRepository extends JpaRepository<UserStoreRole, UUID> {

    @Query("select a from UserStoreRole a where a.user.id = :userId and a.store.id = :storeId")
    Optional<UserStoreRole> find(UUID userId, UUID storeId);

    @Query("select a from UserStoreRole a join fetch a.role where a.user.id = :userId")
    List<UserStoreRole> findOfUser(UUID userId);

    /** Konta z tą rangą, które dalej mają dostęp do jej sklepu i nie są usunięte. */
    @Query("select count(a) from UserStoreRole a join a.user u where a.role.id = :roleId and u.deletedAt is null"
            + " and exists (select 1 from AppUser x join x.stores s where x = u and s = a.store)")
    long countActiveWithRole(UUID roleId);

    @Query("select a.role.id, count(a) from UserStoreRole a join a.user u where a.store.id = :storeId"
            + " and u.deletedAt is null and exists (select 1 from AppUser x join x.stores s where x = u and s = a.store)"
            + " group by a.role.id")
    List<Object[]> countPerRole(UUID storeId);
}
