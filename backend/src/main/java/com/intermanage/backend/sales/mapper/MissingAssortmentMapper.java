package com.intermanage.backend.sales.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.sales.dto.MissingAssortmentCategoryResponse;
import com.intermanage.backend.sales.entity.MissingAssortmentCategory;

@Mapper
public interface MissingAssortmentMapper {

    @Mapping(target = "storeId", source = "category.store.id")
    @Mapping(target = "storeNumber", source = "category.store.number")
    @Mapping(target = "storeName", source = "category.store.name")
    MissingAssortmentCategoryResponse toResponse(MissingAssortmentCategory category, boolean own, long articleCount);
}
