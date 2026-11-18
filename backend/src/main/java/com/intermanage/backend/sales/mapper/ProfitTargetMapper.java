package com.intermanage.backend.sales.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.sales.dto.ProfitTargetResponse;
import com.intermanage.backend.sales.entity.ProfitTarget;

@Mapper
public interface ProfitTargetMapper {

    @Mapping(target = "createdByName", source = "createdBy.displayName")
    ProfitTargetResponse toResponse(ProfitTarget target);
}
