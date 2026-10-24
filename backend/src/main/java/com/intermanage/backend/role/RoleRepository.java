package com.intermanage.backend.role;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r order by r.owner desc, lower(r.name)")
    List<Role> findAllWithPermissions();

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r where r.id = :id")
    Optional<Role> findWithPermissionsById(Long id);

    Optional<Role> findByOwnerTrue();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
