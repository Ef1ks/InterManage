package com.intermanage.backend.sales.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.sales.dto.SlowMovingExcludedItem;
import com.intermanage.backend.sales.entity.SlowMovingExcludedArticle;

@Mapper
public interface SlowMovingExcludedMapper {

    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "excludedBy", source = "excludedBy.displayName")
    SlowMovingExcludedItem toItem(SlowMovingExcludedArticle article);
}
