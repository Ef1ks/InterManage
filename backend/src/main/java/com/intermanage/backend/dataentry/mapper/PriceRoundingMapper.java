package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dataentry.dto.PriceRoundingRuleResponse;
import com.intermanage.backend.dataentry.entity.PriceRoundingRule;

@Mapper
public interface PriceRoundingMapper {

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    PriceRoundingRuleResponse toResponse(PriceRoundingRule rule);
}
