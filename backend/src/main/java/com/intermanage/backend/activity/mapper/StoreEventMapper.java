package com.intermanage.backend.activity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.activity.component.StoreEventCatalog;
import com.intermanage.backend.activity.dto.StoreEventResponse;
import com.intermanage.backend.activity.entity.StoreEvent;

@Mapper(imports = StoreEventCatalog.class)
public interface StoreEventMapper {

    @Mapping(target = "moduleLabel", expression = "java(StoreEventCatalog.label(event.getModule()))")
    StoreEventResponse toResponse(StoreEvent event, String userName);
}
