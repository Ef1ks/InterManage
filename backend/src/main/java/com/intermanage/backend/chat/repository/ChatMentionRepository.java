package com.intermanage.backend.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatMention;

public interface ChatMentionRepository extends JpaRepository<ChatMention, UUID> {

    @Query("select m from ChatMention m where m.message.id in :messageIds order by m.createdAt")
    List<ChatMention> findOfMessages(Collection<UUID> messageIds);

    @Modifying
    @Query(value = """
            insert into chat_mention_recipients (message_id, user_id)
            select :messageId, u.id from users u where u.id in (:userIds)
            on conflict do nothing""", nativeQuery = true)
    int addRecipients(UUID messageId, Collection<UUID> userIds);

    /** Które z tych wiadomości oznaczają konto. */
    @Query(value = """
            select r.message_id from chat_mention_recipients r
            where r.user_id = :userId and r.message_id in (:messageIds)""", nativeQuery = true)
    List<UUID> findMentioning(Collection<UUID> messageIds, UUID userId);

    interface UnreadMentions {
        UUID getConversationId();

        long getUnread();
    }

    /** Nieprzeczytane (i niecofnięte) wiadomości, które oznaczają konto. */
    @Query(value = """
            select m.conversation_id as conversationId, count(*) as unread
            from chat_mention_recipients x
            join chat_messages m on m.id = x.message_id
            join chat_read_states r on r.conversation_id = m.conversation_id and r.user_id = :userId
            where x.user_id = :userId and m.conversation_id in (:conversationIds) and m.retracted_at is null
              and m.created_at > r.last_read_at
            group by m.conversation_id""", nativeQuery = true)
    List<UnreadMentions> countUnread(Collection<UUID> conversationIds, UUID userId);
}
