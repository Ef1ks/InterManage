package com.intermanage.backend.collector.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.collector.dto.StoreCollectorResponse;
import com.intermanage.backend.collector.entity.StoreCollector;

@Mapper
public interface StoreCollectorMapper {

    @Mapping(target = "createdBy", source = "collector.createdBy.displayName")
    @Mapping(target = "pairCode", source = "pairCode")
    @Mapping(target = "catalogCurrent", source = "catalogCurrent")
    @Mapping(target = "newVersion", source = "newVersion")
    StoreCollectorResponse toResponse(StoreCollector collector, String pairCode, boolean catalogCurrent,
            String newVersion);
}
