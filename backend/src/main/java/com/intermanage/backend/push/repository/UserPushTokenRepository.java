package com.intermanage.backend.push.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.push.entity.UserPushToken;

public interface UserPushTokenRepository extends JpaRepository<UserPushToken, UUID> {

    @Query("select t from UserPushToken t join fetch t.user where t.token = :token and t.deletedAt is null")
    Optional<UserPushToken> findActive(String token);

    @Query("select t from UserPushToken t where t.user.id in :userIds and t.deletedAt is null")
    List<UserPushToken> findActiveOfUsers(Collection<UUID> userIds);

    @Query("select distinct t.user.id from UserPushToken t where t.deletedAt is null")
    List<UUID> findUserIdsWithTokens();

    @Query("select t from UserPushToken t where t.token in :tokens and t.deletedAt is null")
    List<UserPushToken> findActiveByTokens(Collection<String> tokens);
}
