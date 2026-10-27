package com.intermanage.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.ActivationToken;

public interface ActivationTokenRepository extends JpaRepository<ActivationToken, UUID> {

    @EntityGraph(attributePaths = {"user", "user.role", "user.role.permissions"})
    Optional<ActivationToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from ActivationToken t where t.user.id = :userId")
    void deleteByUserId(UUID userId);
}
