package com.intermanage.backend.chat.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.chat.dto.ChatDtos;
import com.intermanage.backend.chat.entity.ChatConversation;
import com.intermanage.backend.chat.entity.ChatMessage;
import com.intermanage.backend.chat.repository.ChatAttachmentRepository;

@Mapper
public interface ChatMapper {

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "name", source = "user.displayName")
    ChatDtos.Person toPerson(AppUser user, String avatarUrl);

    @Mapping(target = "id", source = "conversation.id")
    @Mapping(target = "kind", source = "conversation.kind")
    @Mapping(target = "storeId", source = "conversation.store.id")
    @Mapping(target = "storeNumber", source = "conversation.store.number")
    @Mapping(target = "storeName", source = "conversation.store.name")
    @Mapping(target = "name", source = "conversation.name")
    @Mapping(target = "lastMessageAt", source = "conversation.lastMessageAt")
    ChatDtos.Conversation toConversation(ChatConversation conversation, ChatDtos.Person peer, int memberCount,
            ChatDtos.LastMessage lastMessage, long unread, long unreadMentions, boolean local, boolean canManage,
            boolean canEdit, String logoUrl);

    @Mapping(target = "id", source = "message.id")
    @Mapping(target = "authorId", source = "message.author.id")
    @Mapping(target = "authorName", source = "message.author.displayName")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "createdAt", source = "message.createdAt")
    @Mapping(target = "retractedAt", source = "message.retractedAt")
    @Mapping(target = "retractedByName", source = "message.retractedBy.displayName")
    ChatDtos.Message toMessage(ChatMessage message, String body, String authorAvatarUrl, boolean retracted,
            List<ChatDtos.Attachment> attachments, List<ChatDtos.Mention> mentions, boolean mentionsMe,
            boolean canRetract, boolean canRestore);

    ChatDtos.Attachment toAttachment(ChatAttachmentRepository.Summary summary);
}
