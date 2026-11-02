package com.intermanage.backend.importguide.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.importguide.dto.ImportGuideResponse;
import com.intermanage.backend.importguide.entity.ImportGuide;

@Mapper
public interface ImportGuideMapper {

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    ImportGuideResponse toResponse(ImportGuide guide);
}
