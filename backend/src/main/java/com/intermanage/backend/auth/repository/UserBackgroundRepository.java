package com.intermanage.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.UserBackground;

public interface UserBackgroundRepository extends JpaRepository<UserBackground, UUID> {

    @Query("select b from UserBackground b where b.user.id = :userId and b.removedAt is null")
    Optional<UserBackground> findCurrent(UUID userId);

    /** Opis obowiązującego zdjęcia bez treści, do {@code /api/auth/me}. */
    interface Summary {
        UUID getId();

        Integer getVeilPercent();

        String getStorageKey();
    }

    @Query("""
            select b.id as id, cast(b.veilPercent as Integer) as veilPercent, b.storageKey as storageKey
            from UserBackground b where b.user.id = :userId and b.removedAt is null""")
    Optional<Summary> findSummary(UUID userId);

    @Query("select b from UserBackground b where b.id = :id and b.user.id = :userId")
    Optional<UserBackground> findOwn(UUID id, UUID userId);
}
