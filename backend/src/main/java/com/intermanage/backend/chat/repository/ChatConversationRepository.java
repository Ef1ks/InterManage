package com.intermanage.backend.chat.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatConversation;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    /** Zakłada brakujące czaty sklepów (jeden na sklep, indeks częściowy pilnuje, żeby nie powstał drugi). */
    @Modifying
    @Query(value = """
            insert into chat_conversations (id, kind, store_id, created_at)
            select gen_random_uuid(), 'STORE', s.id, now() from stores s
            where s.id in (:storeIds) and s.active
              and not exists (select 1 from chat_conversations c where c.kind = 'STORE' and c.store_id = s.id)
            on conflict do nothing""", nativeQuery = true)
    int ensureStoreChats(Collection<UUID> storeIds);

    @Query("""
            select c from ChatConversation c join fetch c.store s
            where c.kind = com.intermanage.backend.chat.entity.ChatKind.STORE and s.id in :storeIds
              and s.active = true and c.deletedAt is null""")
    List<ChatConversation> findStoreChats(Collection<UUID> storeIds);

    /** Grupy i rozmowy dwóch osób, w których konto jest członkiem. */
    @Query("""
            select c from ChatConversation c left join fetch c.store
            where c.deletedAt is null
              and exists (select 1 from ChatMember m
                          where m.conversation = c and m.user.id = :userId and m.removedAt is null)""")
    List<ChatConversation> findOfMember(UUID userId);

    @Query("select c from ChatConversation c left join fetch c.store where c.id = :id")
    Optional<ChatConversation> findWithStore(UUID id);

    @Query("""
            select c from ChatConversation c
            where c.kind = com.intermanage.backend.chat.entity.ChatKind.DIRECT and c.directKey = :directKey""")
    Optional<ChatConversation> findDirect(String directKey);

    /**
     * Blokada zakładania rozmowy pary do końca transakcji: gdy obie osoby naraz klikną „Napisz”, druga czeka, aż
     * pierwsza zapisze rozmowę, i dostaje tę samą (bez błędu unikalnego {@code direct_key}).
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockDirect(String key);

    // ---------------------------------------------------------------- odczyty i liczniki

    /** Konto, które nie ma stanu odczytu, zaczyna od teraz (nowy pracownik nie dostaje całej historii jako nowej). */
    @Modifying
    @Query(value = """
            insert into chat_read_states (conversation_id, user_id, last_read_at)
            select c.id, :userId, :at from chat_conversations c where c.id in (:conversationIds)
            on conflict do nothing""", nativeQuery = true)
    int startReading(Collection<UUID> conversationIds, UUID userId, Instant at);

    /** Członkowie bez stanu odczytu czytają od chwili {@code at} (tuż przed nową wiadomością, więc jest dla nich nowa). */
    @Modifying
    @Query(value = """
            insert into chat_read_states (conversation_id, user_id, last_read_at)
            select :conversationId, u.id, :at from users u where u.id in (:userIds)
            on conflict do nothing""", nativeQuery = true)
    int startReadingAt(UUID conversationId, Collection<UUID> userIds, Instant at);

    @Modifying
    @Query(value = """
            insert into chat_read_states (conversation_id, user_id, last_read_at) values (:conversationId, :userId, :at)
            on conflict (conversation_id, user_id)
            do update set last_read_at = greatest(chat_read_states.last_read_at, excluded.last_read_at)""",
            nativeQuery = true)
    int markRead(UUID conversationId, UUID userId, Instant at);

    interface ReadState {
        UUID getUserId();

        Instant getLastReadAt();
    }

    @Query(value = """
            select r.user_id as userId, r.last_read_at as lastReadAt
            from chat_read_states r where r.conversation_id = :conversationId""", nativeQuery = true)
    List<ReadState> findReads(UUID conversationId);

    interface Unread {
        UUID getConversationId();

        long getUnread();
    }

    /** Nieprzeczytane cudze wiadomości (bez cofniętych) od ostatniego odczytu konta. */
    @Query(value = """
            select m.conversation_id as conversationId, count(*) as unread
            from chat_messages m
            join chat_read_states r on r.conversation_id = m.conversation_id and r.user_id = :userId
            where m.conversation_id in (:conversationIds) and m.retracted_at is null
              and m.author_id is distinct from :userId and m.created_at > r.last_read_at
            group by m.conversation_id""", nativeQuery = true)
    List<Unread> countUnread(Collection<UUID> conversationIds, UUID userId);

    interface LastMessage {
        UUID getConversationId();

        UUID getAuthorId();

        String getAuthorName();

        String getBody();

        Instant getCreatedAt();

        Instant getRetractedAt();

        int getAttachmentCount();
    }

    @Query(value = """
            select distinct on (m.conversation_id) m.conversation_id as conversationId, m.author_id as authorId,
                   u.display_name as authorName, m.body as body, m.created_at as createdAt,
                   m.retracted_at as retractedAt,
                   (select count(*) from chat_attachments a where a.message_id = m.id)::int as attachmentCount
            from chat_messages m left join users u on u.id = m.author_id
            where m.conversation_id in (:conversationIds)
            order by m.conversation_id, m.created_at desc, m.id desc""", nativeQuery = true)
    List<LastMessage> findLastMessages(Collection<UUID> conversationIds);

    // ---------------------------------------------------------------- obecność

    @Modifying
    @Query(value = """
            insert into user_presence (user_id, last_seen_at) values (:userId, now())
            on conflict (user_id) do update set last_seen_at = excluded.last_seen_at""", nativeQuery = true)
    int touchPresence(UUID userId);

    interface Seen {
        UUID getUserId();

        Instant getLastSeenAt();
    }

    @Query(value = "select p.user_id as userId, p.last_seen_at as lastSeenAt from user_presence p where p.user_id in (:userIds)",
            nativeQuery = true)
    List<Seen> findSeen(Collection<UUID> userIds);
}
