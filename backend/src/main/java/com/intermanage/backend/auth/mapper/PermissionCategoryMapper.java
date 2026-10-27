package com.intermanage.backend.auth.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.auth.dto.PermissionCategoryResponse;
import com.intermanage.backend.auth.entity.PermissionCategory;

@Mapper
public interface PermissionCategoryMapper {

    @Mapping(target = "code", source = "category")
    @Mapping(target = "label", expression = "java(category.label())")
    @Mapping(target = "description", expression = "java(category.description())")
    PermissionCategoryResponse toResponse(PermissionCategory category);
}
