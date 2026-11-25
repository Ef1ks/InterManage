package com.intermanage.backend.collector.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.collector.dto.CollectorPromotionResponse;
import com.intermanage.backend.collector.entity.CollectorPromotion;

@Mapper
public interface CollectorPromotionMapper {

    @Mapping(target = "id", source = "promotion.id")
    @Mapping(target = "collectorName", source = "promotion.collector.name")
    @Mapping(target = "insertedBy", source = "promotion.insertedBy.displayName")
    @Mapping(target = "labelsQueued", expression = "java(promotion.getLabelsFileId() != null)")
    @Mapping(target = "macro", source = "macro")
    CollectorPromotionResponse toResponse(CollectorPromotion promotion, CollectorPromotionResponse.Macro macro);
}
