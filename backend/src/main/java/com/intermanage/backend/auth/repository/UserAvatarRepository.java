package com.intermanage.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.UserAvatar;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, UUID> {

    @Query("select a from UserAvatar a where a.user.id = :userId and a.removedAt is null")
    Optional<UserAvatar> findCurrent(UUID userId);

    /** Opis obowiązującego zdjęcia bez treści, do {@code /api/auth/me}. */
    interface Summary {
        UUID getId();

        String getStorageKey();
    }

    @Query("""
            select a.id as id, a.storageKey as storageKey
            from UserAvatar a where a.user.id = :userId and a.removedAt is null""")
    Optional<Summary> findSummary(UUID userId);

    @Query("select a from UserAvatar a where a.id = :id and a.user.id = :userId")
    Optional<UserAvatar> findOwn(UUID id, UUID userId);
}
