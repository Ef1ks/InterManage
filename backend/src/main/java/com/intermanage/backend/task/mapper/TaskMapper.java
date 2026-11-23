package com.intermanage.backend.task.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.task.dto.TaskAssigneeResponse;
import com.intermanage.backend.task.dto.TaskEventResponse;
import com.intermanage.backend.task.dto.TaskResponse;
import com.intermanage.backend.task.entity.StoreTask;
import com.intermanage.backend.task.entity.StoreTaskEvent;
import com.intermanage.backend.task.entity.StoreTaskItem;
import com.intermanage.backend.task.repository.StoreTaskAttachmentRepository;

@Mapper
public interface TaskMapper {

    @Mapping(target = "items", source = "items")
    @Mapping(target = "itemCount", expression = "java(items.size())")
    @Mapping(target = "checkedCount", expression = "java((int) items.stream().filter(TaskResponse.Item::checked).count())")
    @Mapping(target = "forStore", expression = "java(task.isForStore())")
    @Mapping(target = "group", source = "group")
    @Mapping(target = "id", source = "task.id")
    @Mapping(target = "status", source = "task.status")
    TaskResponse toResponse(StoreTask task, TaskResponse.Group group, List<TaskResponse.Item> items,
            List<TaskResponse.Attachment> attachments, boolean canManage, boolean canComplete);

    TaskResponse.Attachment toAttachment(StoreTaskAttachmentRepository.Summary summary, boolean canRemove);

    TaskResponse.Person toPerson(AppUser user);

    @Mapping(target = "checked", expression = "java(item.isChecked())")
    @Mapping(target = "checkedByName", source = "checkedBy.displayName")
    TaskResponse.Item toItem(StoreTaskItem item);

    @Mapping(target = "userName", source = "user.displayName")
    TaskEventResponse toEvent(StoreTaskEvent event);

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "roleName", expression = "java(user.rankNameIn(storeId))")
    TaskAssigneeResponse toAssignee(AppUser user, java.util.UUID storeId);
}
