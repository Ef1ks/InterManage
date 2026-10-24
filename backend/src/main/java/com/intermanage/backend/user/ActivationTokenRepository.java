package com.intermanage.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ActivationTokenRepository extends JpaRepository<ActivationToken, Long> {

    @EntityGraph(attributePaths = {"user", "user.role", "user.role.permissions"})
    Optional<ActivationToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from ActivationToken t where t.user.id = :userId")
    void deleteByUserId(Long userId);
}
