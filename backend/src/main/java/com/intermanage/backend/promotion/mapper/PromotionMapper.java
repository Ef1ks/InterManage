package com.intermanage.backend.promotion.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.promotion.dto.PromotionImportResponse;
import com.intermanage.backend.promotion.entity.PromotionImport;

@Mapper
public interface PromotionMapper {

    @Mapping(target = "importedBy", source = "importedBy.displayName")
    PromotionImportResponse toResponse(PromotionImport promotionImport);
}
