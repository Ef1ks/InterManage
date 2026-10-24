package com.intermanage.backend.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = {"role", "role.permissions"})
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findWithRoleById(Long id);

    @EntityGraph(attributePaths = {"role", "role.permissions"})
    @Query("select u from AppUser u where u.username = :username")
    Optional<AppUser> findWithRoleByUsername(String username);

    @EntityGraph(attributePaths = "role")
    @Query("select u from AppUser u order by lower(u.displayName)")
    List<AppUser> findAllWithRole();

    boolean existsByUsername(String username);

    @Query("select count(u) > 0 from AppUser u where u.email = :email and (:excludedId is null or u.id <> :excludedId)")
    boolean existsByEmailExcluding(String email, Long excludedId);

    long countByRoleId(Long roleId);

    @Query("select count(u) from AppUser u where u.role.owner = true and u.enabled = true and u.id <> :excludedId")
    long countOtherActiveOwners(Long excludedId);

    boolean existsByRoleOwnerTrue();

    @Query("select u.role.id, count(u) from AppUser u group by u.role.id")
    List<Object[]> countUsersPerRole();
}
