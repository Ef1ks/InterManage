package com.intermanage.backend.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.chat.entity.ChatMember;

public interface ChatMemberRepository extends JpaRepository<ChatMember, UUID> {

    @Query("""
            select m from ChatMember m join fetch m.user
            where m.conversation.id in :conversationIds and m.removedAt is null""")
    List<ChatMember> findActive(Collection<UUID> conversationIds);

    @Query("""
            select count(m) > 0 from ChatMember m
            where m.conversation.id = :conversationId and m.user.id = :userId and m.removedAt is null""")
    boolean isMember(UUID conversationId, UUID userId);

    /** Aktywne konta z dostępem do któregoś ze sklepów (przypisane do sklepu) i właściciele. */
    @Query("""
            select u from AppUser u join fetch u.role
            where u.enabled = true
              and (u.role.owner = true
                   or exists (select 1 from AppUser a join a.stores s
                              where a = u and s.id in :storeIds and s.active = true))
            order by lower(u.displayName)""")
    List<AppUser> findUsersOfStores(Collection<UUID> storeIds);

    /** Aktywne konta przypisane do sklepu, bez właścicieli (właściciel ma dostęp do wszystkich sklepów). */
    @Query("""
            select u.id from AppUser u join u.stores s
            where u.enabled = true and s.id = :storeId and s.active = true""")
    List<UUID> findAssignedIds(UUID storeId);
}
