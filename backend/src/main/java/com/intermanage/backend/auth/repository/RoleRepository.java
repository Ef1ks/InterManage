package com.intermanage.backend.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.Role;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r order by r.owner desc, lower(r.name)")
    List<Role> findAllWithPermissions();

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r where r.id = :id")
    Optional<Role> findWithPermissionsById(UUID id);

    Optional<Role> findByOwnerTrue();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
