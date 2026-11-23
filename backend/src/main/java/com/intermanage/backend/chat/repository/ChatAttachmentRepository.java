package com.intermanage.backend.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatAttachment;
import com.intermanage.backend.chat.entity.ChatAttachmentKind;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, UUID> {

    /** Opis załącznika bez treści, do list wiadomości. */
    interface Summary {
        UUID getId();

        UUID getMessageId();

        ChatAttachmentKind getKind();

        String getFileName();

        String getContentType();

        long getSizeBytes();
    }

    @Query("""
            select a.id as id, a.message.id as messageId, a.kind as kind, a.fileName as fileName,
                   a.contentType as contentType, a.sizeBytes as sizeBytes
            from ChatAttachment a where a.message.id in :messageIds order by a.createdAt""")
    List<Summary> findOfMessages(Collection<UUID> messageIds);

    @Query("""
            select a from ChatAttachment a join fetch a.message m join fetch m.conversation c left join fetch c.store
            where a.id = :id""")
    Optional<ChatAttachment> findWithMessage(UUID id);
}
