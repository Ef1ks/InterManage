package com.intermanage.backend.feedback.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.feedback.dto.FeedbackResponse;
import com.intermanage.backend.feedback.entity.FeedbackReport;
import com.intermanage.backend.feedback.repository.FeedbackAttachmentRepository;

@Mapper
public interface FeedbackMapper {

    @Mapping(target = "storeId", source = "report.store.id")
    @Mapping(target = "storeNumber", source = "report.store.number")
    @Mapping(target = "storeName", source = "report.store.name")
    @Mapping(target = "createdByName", source = "report.createdBy.displayName")
    @Mapping(target = "statusChangedByName", source = "report.statusChangedBy.displayName")
    FeedbackResponse toResponse(FeedbackReport report, List<FeedbackResponse.Attachment> attachments);

    FeedbackResponse.Attachment toAttachment(FeedbackAttachmentRepository.Summary summary);
}
